;;;; swank-clojure.clj --- Swank server for Clojure
;;;
;;; Copyright (C) 2008 Jeffrey Chu
;;;
;;; This file is licensed under the terms of the GNU General Public
;;; License as distributed with Emacs (press C-h C-c to view it).
;;;
;;; Instructions for use:
;;;
;;;   1. First you need to have the latest SVN copy of clojure and the
;;;      latest CVS version of slime (this has been untested for any
;;;      other version). (Latest as of 2008-05-08)
;;;
;;;   2. Use a script that will start clojure while providing java a
;;;      pid, name it "clojure", and place it in your PATH. If no pid
;;;      is provided, swank-clojure will fallback on JDK
;;;      implementation specific methods of getting the pid. It may
;;;      not work on other JVMs.
;;;
;;;      For an example of a script that does this, see
;;;      sh-script/clojure in the clojure-extra repository at:
;;;         http://clojure.codestuffs.com/
;;;
;;;      If this is not possible, you must edit swank-clojure.el and
;;;      change the slime-lisp-implementations.
;;;
;;;   3. If you haven't already, set the default lisp to load for
;;;      slime via slime-lisp-implementations. If you want the default
;;;      to be clojure, you may skip this step.
;;;
;;;      Example for sbcl:
;;;        (setq slime-lisp-implementations '((sbcl "sbcl")))
;;;
;;;   4. Add to your .emacs
;;;        (add-to-list 'load-path "/path/to/swank-clojure")
;;;        (require 'swank-clojure)
;;;
;;;   5. Start slime by:
;;;        M-- M-x slime clojure
;;;
;;;
;;; This is a preliminary version that doesn't support several slime
;;; features, but I'm working on it...
;;;
;;; Known issues:
;;;  - Missing inspection, debugging, and a few other slime functions
;;;  - Swank errors (non-existent functions etc) are displaying in the repl
;;;

(clojure/in-ns 'clojure)

;; This dirties the clojure namespace a little, but unfortunately we need
;; the CL compatibility (t is thrown around by emacs to represent true)
;; I may in the future write another ugly hack (like for the namespace issue)
(def t true)

(clojure/in-ns 'swank)
(clojure/refer 'clojure :exclude '(load-file))

(import '(java.io InputStreamReader PushbackReader StringReader Reader
                  BufferedReader FileReader
                  OutputStreamWriter FileWriter Writer StringWriter
                  OutputStream PrintStream File)
        '(clojure.lang LineNumberingPushbackReader)
        '(java.net ServerSocket Socket InetAddress)
        '(java.util.zip ZipFile)
        ; '(sun.jvmstat.monitor MonitoredHost)
        ; '(com.sun.jdi VirtualMachine)
        )



;;;; General coding utils

; (put 'flet 'clojure-indent-function 1)
(defmacro flet
  "Allows for local function definitions in the following
   format: (flet [(fn name [args] .. body)] .. )."
  ([fns & body]
     (let [fn-name
           (fn fn-name [fn-seq]
             (second fn-seq))
           defs (apply vector (mapcat list (map fn-name fns) fns))]
       `(let ~defs ~@body))))

(defmacro one-of? [val & possible]
  (let [v (gensym)]
    `(let [~v ~val]
       (or ~@(map (fn [p] `(= ~v ~p)) possible)))))

(defmacro def-once [var val]
  `(let [v# (def ~var)]
     (when-not (. v# isBound)
       (. v# bindRoot ~val))))

(defn ffilter
  "Returns the first entry that's true for fn"
  ([coll] (ffilter identity coll))
  ([fn coll]
     (first (filter fn coll))))

;;;; Global variables / constants / configs

;; The default server port
(def #^Integer *default-server-port* 4005)

;; The default charset of the connections (not used)
; (def #^String *default-charset* "iso-8859-1")

;; Whether debug is on
(def #^Boolean *swank-debug?* true)

;; Whether to use redirect io or not
(def #^Boolean *redirect-io* true)

;; Whether to use dedicated output stream or not
(def *use-dedicated-output-stream* nil)

;; What port to use if dedicated output stream is enabled
(def *dedicated-output-stream-port* nil)

;; The default communication style
(def *communication-style* :spawn)

;; Whether to stop accepting connections after the first or not
(def *dont-close* nil)

;; The default coding system
(def *coding-system* "iso-latin-1-unix")

;; A map of all listener sockets, with ports as the key
(def-once *listener-sockets* (ref {}))

;; Whether to log or not
(def *log-events* false)

;; Where to log the output
(def #^PrintStream *log-output* (. System err))

;; The loopback interace address
(def *loopback-interface* "127.0.0.1")

;; A list of all connections for this swank server
(def-once *connections* (ref nil))

;; The current emacs connection
(def *emacs-connection* nil)

;; The regular expression that matches namespace so it may be rewritten
(def *namespace-re* (re-pattern "(^\\(:emacs-rex \\([a-zA-Z][a-zA-Z0-9]+):"))

;; The running unique ids of input requests
(def-once *read-input-catch-tag* (ref 0))

;; A map of catch-tag ids and exceptions
(def-once *read-input-catch-tag-intern* (ref {}))

;; The state stack, whatever that is
(def *swank-state-stack* '())

;; The package in which all swank io is read with
(def *swank-io-package* (create-ns 'swank-io-package))

;; A list of currently active threads
(def *active-threads* (ref nil))

;; The protocol version (to ignore)
(def *protocol-version* (ref "just say yes"))


;;;; Threads

(defn spawn
  ([#^Runnable f]
     (let [calling-ns *ns*
           calling-out *out*]
       (doto (new Thread
                  (fn []
                    (binding [*ns* calling-ns
                              *out* calling-out]
                      (f))))
         (start))))
  ([#^Runnable f #^String name]
     (let [calling-ns *ns*
           calling-out *out*]
       (doto (new Thread
                  (fn []
                    (binding [*ns* calling-ns
                              *out* calling-out]
                      (f)))
                  name)
         (start)))))

(defn current-thread
  ([]
     (. Thread currentThread))
  {:tag Thread})

(defn thread-name [#^Thread t]
  (. t getName))

(defn sleep-milli
  "Sleeps for a given number of milliseconds"
  ([#^Long time]
     (. Thread sleep time)))

(defn sleep
  "Sleeps for a given number of seconds"
  ([#^Integer time]
     (sleep-milli (* time 1000))))

(defn list-threads
  "Returns a list of currently running threads. It's a pretty sketchy
   way of doing it right now, but it seems to work."
  ([]
     (map key (seq (. Thread getAllStackTraces)))))

(defn find-thread
  ([name]
     (ffilter #(= name (thread-name %)) (list-threads)))
  {:tag Thread})

(defn thread-alive?
  ([#^Thread thread]
     (. thread isAlive))
  {:tag Boolean})

(defn kill-thread
  ([#^Thread thread]
     (. thread interrupt)))


;;;; Socket programming

(defn create-socket
  "Creates a ServerSocket based on a host and port"
  ([host port]
     (new ServerSocket port 0 (. InetAddress getByName host)))
  {:tag ServerSocket})

(defn local-port
  "Returns the local port of a ServerSocket."
  ([#^ServerSocket socket]
     (. socket getLocalPort))
  {:tag Integer})

(defn close-socket [#^ServerSocket socket]
  (. socket close))

(defn accept-connection
  "Accepts an incoming connection on a ServerSocket"
  ([#^ServerSocket socket]
     (. socket accept))
  {:tag Socket})


;;;; IO / Stream utils

(defn read-chars
  "Reads a given amount of characters out of a reader."
  ([#^java.io.InputStream reader #^Integer num-chars]
     (let [sb (new StringBuilder num-chars)]
       (dotimes count num-chars
         (. sb (append (char (. reader (read))))))
       (str sb)))
  {:tag String})

(defn read-from-string
  "Reads the next object from a string."
  ([string]
     (with-open rdr (new LineNumberingPushbackReader (new StringReader string))
       (read rdr))))


;;;; Logging
(defn log-event [& strs]
  (when *log-events*
    (doto *log-output*
      (print (apply str strs))
      (flush))))


;;;; System interface

(defn get-pid
  "Returns the PID of the JVM. This may or may not be accurate
   depending on the JVM in which clojure is running off of."
  ([]
     (or (. System (getProperty "pid"))
         (first (.. java.lang.management.ManagementFactory (getRuntimeMXBean) (getName) (split "@")))))
  {:tag String})

(defn user-home-path []
  (. System getProperty "user.home"))


;;;; Swank utils

(defn fix-namespace
  ([text]
     (let [m (re-matcher *namespace-re* text)]
       (. m (replaceAll "$1/")))))

(defn hex->num
  "Converts a hex string into an integer"
  ([#^String hex-str]
     (. Integer (parseInt hex-str 16)))
  {:tag Integer})

(defn num->hex
  "Converts a number to a hex string. If a minimum length is provided,
   the hex number will be left padded with 0s."
  ([num]
     (. Integer (toHexString num)))
  ([num min-len]
     (let [hex (num->hex num)
           len (count hex)]
       (if (< len min-len)
         (str (apply str (replicate (- min-len len) \0)) hex)
         hex)))
  {:tag String})

(defn ignore-protocol-version [version]
  (dosync (ref-set *protocol-version* version)))


;;;; Bad stream mojo (but better than before)
(defn out-fn-stream [out-fn]
  (let [closed? (ref nil)
        #^StringWriter stream
        (proxy [java.io.StringWriter] []
          (close []
            (dosync
             (ref-set closed? true)))
          (flush []
            (let [#^StringWriter me this] ;; only so it know what it is
              (let [len (.. me getBuffer length)]
                (when (> len 0)
                  (out-fn (.. me getBuffer (substring 0 len)))
                  (.. me getBuffer (delete 0 len)))))))]
   (spawn
    (fn []
      (loop []
        (. Thread (sleep 200))
        (when-not @closed?
          (. stream flush)
          (recur))))
    (str (gensym "Out stream flusher ")))
   stream))



;;;; Raw Message Encoding
(defn maybe-out-stream
  ([stream]
     (cond
      (instance? Socket stream) (let [#^Socket s stream] (. s getOutputStream))
      :else stream))
  {:tag OutputStream})

(defn encode-message
  "Encodes a message into SWANK format"
  ([message stream]
     (log-event "*jochu* ENCODE MESSAGE " (pr-str message) "\n")
     (log-event "*jochu*      TO STREAM " (pr-str stream) "\n")
     (let [#^String string (pr-str message)
           length (count string)]
       (doto (maybe-out-stream stream)
         (write (. (num->hex length 6) getBytes))
         (write (. string getBytes))
         (flush)))))

(defn read-form [string]
  (binding [*ns* *swank-io-package*]
    (read-from-string string)))

(defn decode-message-length [#^Socket stream]
  (let [string (read-chars (. stream getInputStream) 6)]
    (hex->num string)))


(defn decode-message
  "Read an s-expression from stream using the slime protocol"
  ([#^Socket stream]
     (binding [*swank-state-stack* (cons :read-next-form *swank-state-stack*)]
       (let [length (decode-message-length stream)
             string (read-chars (. stream getInputStream) length)]
         (log-event "*jochu* READ: " string "\n")
         (read-form (fix-namespace string))))))


;;;; Mailboxes (message queues)

;; Holds references to the mailboxes (message queues) for a thread
(def *mailboxes* (ref {}))

(defn mbox
  "Returns a mailbox for a thread. Creates one if one does not already exist."
  ([#^Thread thrd]
     (dosync
      (when-not (@*mailboxes* thrd)
        (alter
         *mailboxes* assoc
         thrd (new java.util.concurrent.LinkedBlockingQueue))))
     (@*mailboxes* thrd))
  {:tag java.util.concurrent.LinkedBlockingQueue})

(defn mb-send
  "Sends a message to a given thread"
  ([#^Thread thrd message]
     (let [mbox (mbox thrd)]
       (. mbox put message))))

(defn mb-receive
  "Blocking recieve for messages for the current thread"
  ([]
     (let [mb (mbox (current-thread))]
       (. mb take))))



;;;; Swank connection object

(defstruct connection
  ;; Raw I/O stream of socket connection.
  :socket-io
  ;; Optional dedicated output socket (backending `user-output' slot).
  ;; Has a slot so that it can be closed with the connection.
  :dedicated-output nil
  ;; Streams that can be used for user interaction, with requests
  ;; redirected to Emacs.
  :user-input :user-output :user-io
  ;; A stream that we use for *trace-output*; if nil, we user user-output.
  :trace-output
  ;; A stream where we send REPL results.
  :repl-results
  ;; In multithreaded systems we delegate certain tasks to specific
  ;; threads. The `reader-thread' is responsible for reading network
  ;; requests from Emacs and sending them to the `control-thread'; the
  ;; `control-thread' is responsible for dispatching requests to the
  ;; threads that should handle them; the `repl-thread' is the one
  ;; that evaluates REPL expressions. The control thread dispatches
  ;; all REPL evaluations to the REPL thread and for other requests it
  ;; spawns new threads.
  :reader-thread :control-thread :repl-thread
  ;; Callback functions:
  ;; (SERVE-REQUESTS <this-connection>) serves all pending requests
  ;; from Emacs.
  :server-requests
  ;; (READ) is called to read and return one message from Emacs.
  :read
  ;; (SEND OBJECT) is called to send one message to Emacs.
  :send
  ;; (CLEANUP <this-connection>) is called when the connection is
  ;; closed.
  :cleanup
  ;; Cache of macro-indentation information that has been sent to Emacs.
  ;; This is used for preparing deltas to update Emacs's knowledge.
  ;; Maps: symbol -> indentation-specification
  :indentation-cache
  ;; The list of packages represented in the cache:
  :indentation-cache-packages
  ;; The communication style used.
  :communication-style
  ;; The coding system for network streams.
  :coding-system)


;;;; Communication between emacs and swank server

;;; Send to emacs
(defn send-to-emacs
  "Send an object to the current emacs connection"
  ([obj]
     ((*emacs-connection* :send) obj)))

(def send-oob-to-emacs #'send-to-emacs)

(defn send-to-control-thread
  "Send an object to the current emacs-connection's control-thread"
  ([obj]
     (mb-send @(*emacs-connection* :control-thread) obj)))

;;; Read from emacs
(defn read-from-control-thread []
  (mb-receive))

(defn read-from-emacs []
  (let [call ((*emacs-connection* :read))]
    (log-event "*jochu* CALL:" (pr-str (doall call)) "\n")
    (apply (find-var (first call)) (rest call))))

(defn intern-catch-tag [tag]
  (dosync
   (when-not (@*read-input-catch-tag-intern* tag)
     (alter *read-input-catch-tag-intern*
            assoc tag (new Exception))))
  (@*read-input-catch-tag-intern* tag))

(defn read-user-input-from-emacs []
  (let [tag (dosync (alter *read-input-catch-tag* inc))]
    (send-to-emacs `(:read-string ~(thread-name (current-thread)) ~tag))
    (try
     (loop [] (read-from-emacs) (recur))
     (catch Exception e
       (let [#^Exception e e]
         (if (= (. e getCause) (intern-catch-tag tag))
           (. e getMessage)
           (throw e))))
     (catch Throwable t
       (send-to-emacs `(:read-aborted ,(thread-name (current-thread)) ~tag))))))

(defn y-or-n-p-in-emacs [& strs]
  (let [tag (dosync (alter *read-input-catch-tag* inc))
        question (apply str strs)]
    (send-to-emacs `(:y-or-n-p ~(thread-name (current-thread)) ~tag ~question))
    (try
     (loop [] (read-from-emacs) (recur))
     (catch Exception e
       (let [#^Exception e e]
         (if (= (. e getCause) (intern-catch-tag tag))
           (. e getMessage)
           (throw e))))
     (catch Throwable t
       (send-to-emacs `(:read-aborted ,(thread-name (current-thread)) ~tag))))))

(defn take-input
  "Return the string input to the continuation tag"
  ([tag #^String input]
     (throw (new Exception input (intern-catch-tag tag)))))


;;;; IO Redirection support

(defmacro with-io-redirection
  "Executes the body and redirects all output into an output stream if
   redirect-io is enabled."
  ([connection & body]
     `(maybe-call-with-io-redirection ~connection (fn [] ~@body))))

(defn call-with-redirected-io [connection f]
  (binding [*out* (connection :user-output)]
    (f)))

(defn maybe-call-with-io-redirection [connection f]
  (if *redirect-io*
    (call-with-redirected-io connection f)
    (f)))


;;;; Making connections

;; prototype (defined later)
(def accept-authenticated-connection)

(defmacro with-connection [connection & body]
  `(binding [*emacs-connection* ~connection]
     (with-io-redirection *emacs-connection*
       ~@body)))

(defn open-dedicated-output-stream
  ([socket-io]
     (let [socket (create-socket *loopback-interface* *dedicated-output-stream-port*)
           port (local-port socket)]
       (try
        (encode-message '(:open-dedicated-output-stream ~port) socket-io)
        (let [dedicated (accept-authenticated-connection socket)]
          (close-socket socket)
          dedicated)
        (finally
         (when (and socket (not (. socket isClosed)))
           (close-socket socket))))))
  {:tag OutputStream})

(defn make-output-function
  "Create function to send user output to Emacs. This function may
   open a dedicated socket to send output. 

   It returns [the output function, the dedicated stream (or nil if
   none was created)]"
  ([connection]
     (if *use-dedicated-output-stream*
       (let [stream (open-dedicated-output-stream (connection :socket-io))]
         ;; todo - This doesn't work...
         [(fn [#^String string] (doto stream (write (. string getBytes)) (flush))) stream])
       [(fn [string] (with-connection connection (send-to-emacs `(:write-string ~string)))) nil])))


(defn open-streams
  "Return the 5 streams for IO redirection: 
     :dedicated-output, :input, :output, :io, :repl-results"
  ([connection]
     (let [[output-fn dedicated-output] (make-output-function connection)
           input-fn (fn [] (with-connection connection (read-user-input-from-emacs)))
           repl-results (fn [string] (with-connection connection (send-to-emacs `(:write-string ~string :repl-result))))]
       [dedicated-output input-fn output-fn [input-fn output-fn] repl-results])))

(defn initialize-streams-for-connection
  "Initiliazes streams needed for a connection (dedicated-output,
   input, output, io, and repl-results)"
  ([connection]
     (let [[dedicated in out io repl] (open-streams connection)]
       (assoc connection
         :dedicated-output dedicated
         :user-input in
         :user-output (out-fn-stream out)
         :user-io io
         :repl-results repl))))

(defn handle-request
  "Read and process on request. The processing is done in the extent
  of the toplevel restart."
  ([connection]
     (binding [*swank-state-stack* '(:handle-request)]
       (read-from-emacs))))

(defn repl-loop [connection]
  (loop [] (handle-request connection) (recur)))

(defn spawn-worker-thread [connection]
  ;; something about with-bindings business
  (spawn (fn [] (with-connection connection (handle-request connection)))
         (str (gensym "Worker thread "))))

(defn spawn-repl-thread [connection name]
  (spawn (fn [] (with-connection connection (repl-loop connection)))
         name))

(defn repl-thread [connection]
  (let [thread @(connection :repl-thread)]
    (when-not thread
      (log-event "ERROR: repl-thread is nil"))
    (cond
     (thread-alive? thread) thread
     :else (dosync (ref-set (connection :repl-thread)
                            (spawn-repl-thread connection "new-repl-thread"))))))

(defn thread-for-evaluation [id]
  (cond
   (= id 't) (spawn-worker-thread *emacs-connection*)
   (= id :repl-thread) (repl-thread *emacs-connection*)
   :else (find-thread id)))

(defn find-worker-thread [id]
  (cond
   (one-of? id 't true) (first @*active-threads*)
   (= id :repl-thread) (repl-thread *emacs-connection*)
   :else (find-thread id)))

(defn interrupt-worker-thread [id]
  (let [#^Thread thread (or (find-worker-thread id)
                            (repl-thread *emacs-connection*))]
    (. thread interrupt)))

(defn dispatch-event [event socket-io]
  (log-event "DISPATCHING: " (pr-str event) "\n")
  (let [[ev & args] event]
    (cond
     (= ev :emacs-rex)
     (let [[form package thread-id id] args]
       (let [thread (thread-for-evaluation thread-id)]
         (dosync ;; add thread to active-threads
          (alter *active-threads* conj thread))
         (mb-send thread `(eval-for-emacs ~form ~package ~id))))
     
     (= ev :return)
     (let [[thread & args] args]
       (dosync ;; Remove thread from active-threads
        (let [n (count @*active-threads*)
              remaining (filter #(not= (thread-name %) thread) @*active-threads*)]
          (ref-set *active-threads* remaining)))
       (encode-message `(:return ~@args) socket-io))

     (= ev :emacs-interrupt)
     (let [[thread-id] args]
       (interrupt-worker-thread thread-id))

     (one-of? ev
              :debug :debug-condition :debug-activate :debug-return)
     (let [[thread & args] args]
       (encode-message `(~ev ~(thread-name thread) ~@args) socket-io))
     
     (one-of? ev
              :write-string :presentation-start :presentation-end
              :new-package :new-features :ed :percent-apply :indentation-update
              :eval-no-wait :background-message :inspect)
     (encode-message event socket-io)
     
     :else (log-event "*jochu* UNHANDLED EVENT " (pr-str event) "\n"))))

(defn dispatch-loop [socket-io connection]
  (binding [*emacs-connection* connection]
    (loop []
      (dispatch-event (mb-receive) socket-io)
      (recur))))

(defn read-loop [control-thread input-stream connection]
  ;; with-reader-error-handler
  (loop [] (mb-send control-thread (decode-message input-stream)) (recur)))

(defn spawn-threads-for-connection [connection]
  (let [socket-io (connection :socket-io)
        control-thread (spawn (fn [] (dispatch-loop socket-io connection)))]
    (dosync
     (ref-set (connection :control-thread) control-thread))
    (let [reader-thread (spawn (fn []
                                 (let [go (mb-receive)]
                                   (assert (= go 'accept-input)))
                                 (read-loop control-thread socket-io connection)))
          repl-thread (spawn-repl-thread connection "repl-thread")]
      (dosync
       (ref-set (connection :repl-thread) repl-thread)
       (ref-set (connection :reader-thread) reader-thread))
      (mb-send reader-thread 'accept-input)
      connection)))

(defn cleanup-connection-threads [connection]
  (let [threads (map connection [:repl-thread :reader-thread :control-thread])]
    (doseq thread threads
      (when (and thread
                 (thread-alive? thread)
                 (not= (current-thread) thread))
        (kill-thread thread)))))

(defn create-connection [socket-io style]
  (initialize-streams-for-connection
   (cond
    (= style :spawn) (struct-map connection
                       :socket-io socket-io
                       :read read-from-control-thread
                       :send send-to-control-thread
                       :serve-requests spawn-threads-for-connection
                       :cleanup cleanup-connection-threads
                       :communication-style style
                       :control-thread (ref nil)
                       :reader-thread (ref nil)
                       :repl-thread (ref nil)
                       :indentation-cache (ref nil)
                       :indentation-cache-packages (ref nil))
    :else (comment
            (struct-map :socket-io socket-io
                        :read read-from-socket-io
                        :send send-to-socket-io
                        :serve-requests simple-serve-requests
                        :communication-style style)))))


(defn simple-announce-function [port]
  (when *swank-debug?*
    (. *log-output* println (str "Swank started at port: " port "."))
    (. *log-output* flush)))

(defn slime-secret []
  (try
   ;; We only use this and not slurp because we're lazy about the \n
   (with-open secret (new BufferedReader
                          (new FileReader
                               (str (user-home-path) (. File separator) ".slime-secret")))
     (. secret readLine))
   (catch Throwable e nil)))

(defn accept-authenticated-connection [& args]
  (let [#^Socket new (apply accept-connection args)]
    (try
     (when-let secret (slime-secret)
       (let [first-val (decode-message new)]
         (when-not (= first-val secret)
           (throw (new Exception "Incoming connection doesn't know the password.")))))
     ;; Close connection if something goes wrong
     (catch Throwable e
       (. new close)))
    new))

(defn serve-requests
  "Read and process all requests on connections"
  ([connection]
     ((connection :serve-requests) connection)))

(defn serve-connection [#^ServerSocket socket style dont-close external-format]
  (try
   (let [client (accept-authenticated-connection socket)]
     (when-not dont-close
       (close-socket socket))
     (let [connection (create-connection client style)]
       ; (run-hook *new-connection-hook* connection)
       (dosync
        (alter *connections* conj connection))
       (serve-requests connection)))
   (finally
    (when-not (or dont-close (. socket isClosed))
      (close-socket socket)))))

(defn setup-server [port announce-fn style dont-close external-format]
  (let [socket (create-socket *loopback-interface* port)
        local-port (local-port socket)]
    (announce-fn local-port)
    (flet [(fn serve []
             (try 
              (serve-connection socket style dont-close external-format)
              (catch java.lang.InterruptedException e nil)))]
      (cond
       (= style :spawn) (spawn (fn []
                                 (cond
                                  dont-close (loop [] (serve) (recur))
                                  :else (serve)))
                               (str "Swank " port))
       ;; fd-handler / sigio not supported
       ;; (one-of? style :fd-handler :sigio) (add-fd-handler socket serve)
       :else (loop [] (when dont-close (serve) (recur)))))
    (dosync
     (alter *listener-sockets* assoc port [style socket]))
    local-port))

(defn announce-server-port [#^String file port]
  (with-open out (new FileWriter file)
    (doto out
      (write (str port "\n"))
      (flush)))
  (simple-announce-function port))

(defn start-server
  "Start the serrver and write the listen port number to
   PORT-FILE. This is the entry point for Emacs."
  ([port-file & options]
     (let [{:keys [style dont-close coding-system]
            :or {style *communication-style*
                 dont-close *dont-close*
                 coding-system *coding-system*}} (apply hash-map options)]
       (setup-server
        0 (fn [port] (announce-server-port port-file port))
        style dont-close
        ;; todo - real external format support
        coding-system))))

(defn create-server [& options]
  (let [{:keys [port style dont-close coding-system]
         :or {port *default-server-port*
              style *communication-style*
              dont-close *dont-close*
              coding-system *coding-system*}} (apply hash-map options)]
    (setup-server port simple-announce-function style dont-close coding-system)))


(defn stop-server
  "Stops a server running on port"
  ([port]
     (let [[style #^ServerSocket socket] (*listener-sockets* port)]
       (cond
        (= style :spawn) (do (. (find-thread (str "Swank " port)) interrupt)
                             (. socket close)
                             (dosync (alter *listener-sockets* dissoc port)))
        :else nil))))

(defn restart-server
  "Stop the server listening on port, then start a new swank server on
   port running in style. If dont-close is true, then the listen
   socket will accept multiple connections, otherwise it will be
   closed after the first."
  ([& options]
     (let [{:keys [port] :or {port *default-server-port*}} (apply hash-map options)]
       (stop-server port)
       (sleep 5)
       (apply create-server options))))


;;;; Swank functions!

(def *buffer-package* nil)
(def *pending-continuations* nil)

(defn maybe-ns [package]
  (cond
   (symbol? package) (find-ns package)
   (string? package) (maybe-ns (symbol package))
   (keyword? package) (maybe-ns (name package))
   (instance? clojure.lang.Namespace package) package
   :else (maybe-ns 'clojure)))

(defn guess-package
  "Guess which package corresponds to string. Return nil if no
   matches."
  ([string]
     (maybe-ns string)))

(defn guess-buffer-package
  "Return a package for a string. Fall back to current if no package
   exists."
  ([string]
     (or (and string (guess-package string))
         *ns*)) )

(defn eval-for-emacs
  "Bind *buffer-package* to buffer-package and evaluate form. Return
   the result to the id (continuation). Errors are trapped and invoke
   our non-existent debugger. "
  ([form buffer-package id]
     (try
      (binding [*buffer-package* buffer-package
                *pending-continuations* (cons id *pending-continuations*)]
        (send-to-emacs `(:return ~(thread-name (current-thread))
                                 (:ok ~(eval form))
                                 ~id)))
      (catch Throwable t
        (let [#^Throwable t t]
          (send-to-emacs
           `(:write-string
             ~(with-out-str
               (. t printStackTrace (new java.io.PrintWriter *out*))))))
        (comment
          (send-to-emacs `(:debug ~thread
                                  1
                                  (~(. t getMessage) ~(str "  [Condition of type" (class t) "]") nil)
                                  (("ABORT" "Return to SLIME's top level."))
                                  ((0 ~(with-out-str
                                        (. t printStackTrace (new java.io.PrintWriter *out*)))))
                                  ())))
        (send-to-emacs `(:return ~(thread-name (current-thread))
                                 (:abort)
                                 ~id))))))

(defn connection-info []
  `(:pid ~(get-pid)
    :style ~(*emacs-connection* :communication-style)
    :lisp-implementation (:type "clojure")
    :package (:name ~(ns-name *ns*)
              :prompt ~(ns-name *ns*))
    :version ~(deref *protocol-version*)))

(defn quit-lisp []
  (. System exit 0))



;;;; Require / provide

;; the path of this file (only set on load time), which should be okay.
(def-once clj-source (var-get (. clojure.lang.Compiler SOURCE_PATH)))
(def-once clj-dir (str (. (new File (str swank/clj-source)) getParentFile)))

(def *modules* (ref nil))

(defn provide [module]
  (dosync
   (alter *modules* conj module)))

(defn swank-require
  "Yeah, we don't have modules. Sorry."
  ([modules]
     (swank-require modules clj-dir))
  ([modules pathname]
     (doseq module (if (seq? modules) modules (list modules))
       (when-not (ffilter #(= % module) @*modules*)
         (let [file (str pathname (. java.io.File separator) (name module) ".clj")]
           (when (. (new java.io.File file) exists)
             (clojure/load-file file)))))
     @*modules*))

;;;; Simple arglist display
(defn operator-arglist [name package]
  (let [var (ns-resolve (guess-buffer-package package) (symbol name))]
    (if-let args (and var ((meta var) :arglists))
      (pr-str args)
      nil)))

;;;; Simple completions

(defn vars-start-with
  "Runs through the provided vars and collects a list of the names
   that start with a given pattern."
  ([#^String prefix vars]
     (filter (fn [#^String s]
               (and s (not (. s isEmpty)) (. s startsWith prefix)))
             (map (comp str :name meta) vars))))

(defn common-prefix
  "Returns the largest common prefix"
  ([#^String a #^String b]
     (let [limit (min (count a) (count b))]
       (loop [i 0]
         (cond
          (or (= i limit) (not= (. a (charAt i)) (. b (charAt i)))) (. a (substring 0 i))
          :else (recur (inc i))))))
  {:tag String})

(defn symbol-name-parts
  ([symbol]
     (symbol-name-parts symbol nil))
  ([#^String symbol default-ns]
     (let [ns-pos (. symbol (indexOf (int \/)))]
       (if (< ns-pos 0)
         [default-ns symbol]
         [(. symbol (substring 0 ns-pos))
          (. symbol (substring (+ ns-pos 1)))]))))

(defn simple-completions [string package]
  (try
   (let [[sym-ns sym-name] (symbol-name-parts string)
         ns (maybe-ns (or sym-ns package))
         vars (vals (if sym-ns (ns-publics ns) (ns-map ns)))
         matches (sort (vars-start-with sym-name vars))]
     (send-to-emacs `(:write-string ~(str "sym ns=" sym-ns)))
     (if sym-ns
       (list (map (partial str sym-ns "/") matches)
             (if matches
               (str sym-ns "/" (reduce common-prefix matches))
               string))
       (list matches
             (if matches
               (reduce common-prefix matches)
               string))))
   (catch java.lang.Throwable t
     (send-to-emacs `(:write-string "fail"))
     (list nil string))))


;;;; Evaluation

(defmacro with-buffer-syntax [& body]
  `(binding [*ns* (maybe-ns *buffer-package*)]
     ~@body))

(defn from-string
  "Read a string from inside the *buffer-package*"
  ([string] (with-buffer-syntax (read-from-string string))))

(def format-values-for-echo-area #'pr-str)

(defn eval-region
  "Evaluate string, return the results of the last form as a list and
   a secondary value the last form."
  ([string]
     (with-open rdr (new LineNumberingPushbackReader (new StringReader string))
       (loop [form (read rdr false rdr), value nil, last-form nil]
         (if (= form rdr)
           [value last-form]
           (recur (read rdr false rdr)
                  (eval form)
                  form))))))

(defn interactive-eval [string]
  (with-buffer-syntax
   (let [result (eval (from-string string))]
     (format-values-for-echo-area result))))

(defn interactive-eval-region [string]
  (with-buffer-syntax
   (format-values-for-echo-area (first (eval-region string)))))

(defn track-package [f]
  (let [last-ns *ns*]
    (try
     (f)
     (finally
      (when-not (= last-ns *ns*)
        (send-to-emacs `(:new-package ~(ns-name *ns*) ~(ns-name *ns*))))))))

(defn send-repl-results-to-emacs [val]
  (send-to-emacs `(:write-string ~(str (pr-str val) "\n") :repl-result)))

(def *send-repl-results-function* #'send-repl-results-to-emacs)
(defn repl-eval [string]
  (with-buffer-syntax
   (track-package
    (fn []
      (let [[values last-form] (eval-region string)]
        (*send-repl-results-function* values))))))

(def *listener-eval-function* #'repl-eval)
(defn listener-eval [string]
  (*listener-eval-function* string))

(defn load-file [file-name]
  (pr-str (clojure/load-file file-name)))

(defn compiler-notes-for-emacs []
  ;; todo - not implemented...
  nil)

(defn compile-file-for-emacs [file-name load?]
  (when load?
    (with-buffer-syntax
     (let [start (. System (nanoTime))
           ret (load-file file-name)]
       (list (pr-str ret)
             (str (/ (- (. System (nanoTime)) start) 1000000.0) " m"))))))

;;;; Macroexpansion

(defn apply-macro-expander [expander string]
  (with-buffer-syntax
   (pr-str (expander (from-string string)))))

(defn swank-macroexpand-1 [string]
  (apply-macro-expander macroexpand-1 string))

(defn swank-macroexpand [string]
  (apply-macro-expander macroexpand string))

;; not implemented yet, needs walker
(defn swank-macroexpand-all [string]
  (apply-macro-expander macroexpand string))

;;;; Packages
(defn list-all-package-names
  ([] (map (comp str ns-name) (all-ns)))
  ([nicknames?] (list-all-package-names)))

(defn set-package [name]
  (let [ns (maybe-ns name)]
    (in-ns (ns-name ns))
    (list (ns-name ns)
          (ns-name ns))))

;;;; Describe
(defn describe-to-string [var]
  (with-out-str
   (print-doc var)))

(defn describe-symbol [symbol-name]
  (with-buffer-syntax
   (if-let v (resolve (symbol symbol-name))
     (describe-to-string v)
     (str "Unknown symbol " symbol-name))))

(def describe-function #'describe-symbol)

;; Only one namespace... so no kinds
(defn describe-definition-for-emacs [name kind]
  (describe-symbol name))

;; Only one namespace... so only describe symbol
(defn documentation-symbol
  ([symbol-name default] (documentation-symbol))
  ([symbol-name]
     (describe-symbol symbol-name)))


;;;; Source Locations

(defn set-default-directory [directory & ignore]
  ;; incomplete solution, will change search path for find-definitions
  ;; but will not work for load-file etc.
  (. System (setProperty "user.dir" directory))
  directory)

(defn slime-find-file-in-dir [#^File file #^String dir]
  (let [file-name (. file (getPath))
        child (new File (new File dir) file-name)]
    (or (when (. child (exists))
          `(:file ~(. child (getPath))))
        (try
         (let [zipfile (new ZipFile dir)]
           (when (. zipfile (getEntry file-name))
             `(:zip ~dir ~file-name)))
         (catch java.lang.Throwable e false)))))

(defn slime-find-file-in-paths [#^String file paths]
  (let [f (new File file)]
    (if (. f (isAbsolute))
      `(:file ~file)
      (first (filter identity (map (partial slime-find-file-in-dir f) paths))))))

(defn get-path-prop [prop]
  (seq (.. System
           (getProperty prop)
           (split (. File pathSeparator)))))

(defn slime-search-paths []
  (concat (get-path-prop "user.dir")
          (get-path-prop "java.class.path")
          (get-path-prop "sun.boot.class.path")))

(defn find-definitions-for-emacs [name]
  (let [sym-name (from-string name)
        metas (map meta (vals (ns-map (maybe-ns *buffer-package*))))
        definition
        (fn definition [meta]
          (if-let path (slime-find-file-in-paths (:file meta) (slime-search-paths))
            `(~(str "(defn " (:name meta) ")")
              (:location
               ~path
               (:line ~(:line meta))
               nil))
            `(~(str (:name meta))
              (:error "Source definition not found."))))]
    (map definition (filter #(= (:name %) sym-name) metas))))




;;;; source file cache (not needed)
(defn buffer-first-change [& ignore] nil)

;;;; Not really a debugger
(defn throw-to-top-level []
  "Restarted!")