;;   Copyright (c) Markus Gustavsson. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns clj-native.core
  (:use [clojure.contrib.seq-utils :only [indexed]])
  (:import [clojure.asm ClassVisitor MethodVisitor FieldVisitor
            ClassWriter Opcodes Type]
           [java.util UUID]
           [java.nio ByteBuffer IntBuffer CharBuffer
            ShortBuffer LongBuffer FloatBuffer DoubleBuffer]
           [com.sun.jna Native Library NativeLong
            Pointer WString Structure Union Callback]))

;;; ***************************************************************************
;;;
;;; -----===== Code loading =====-----
;;;
;;; ***************************************************************************

(defn get-root-loader
  "Gets the root DynamicClassLoader"
  []
  (loop [#^ClassLoader cl (clojure.lang.RT/baseLoader)]
    (let [parent (.getParent cl)]
      (if (not= (class parent) clojure.lang.DynamicClassLoader)
        cl
        (recur parent)))))

(defn load-code
  "Loads bytecode (a class definition) using the root DynamicClassLoader"
  [#^String classname #^"[B" bytecodes]
  (.defineClass #^ClassLoader (get-root-loader) classname bytecodes))

;;; ***************************************************************************
;;;
;;; -----===== Types =====-----
;;;
;;; ***************************************************************************

(defn native-long
  "Returns the java type corresponding to
  the bitsize of a native long."
  []
  (condp = NativeLong/SIZE
    4 Integer/TYPE
    8 Long/TYPE))

(defn native-long-buffer
  "Returns the type of nio buffer appropriate
  to store arrays of native longs."
  []
  (condp = NativeLong/SIZE
    4 IntBuffer
    8 LongBuffer))

(def type-map
     {'char Byte/TYPE
      'wchar_t Character/TYPE
      'byte Byte/TYPE
      'short Short/TYPE
      'int Integer/TYPE
      'enum Integer/TYPE
      'BOOL Boolean/TYPE
      'bool Boolean/TYPE
      'size_t native-long
      'long native-long
      'longlong Long/TYPE
      '__int64 Long/TYPE
      'i8 Byte/TYPE
      'i16 Short/TYPE
      'i32 Integer/TYPE
      'i64 Long/TYPE
      'float Float/TYPE
      'double Double/TYPE
      'void Void/TYPE
      'void* Pointer
      'byte* ByteBuffer
      'char* ByteBuffer
      'constchar* String
      'wchar_t* CharBuffer
      'constwchar_t* WString
      'short* ShortBuffer
      'int* IntBuffer
      'long* native-long-buffer
      'size_t* native-long-buffer
      'longlong* LongBuffer
      '__int64* LongBuffer
      'i8* ByteBuffer
      'i16* ShortBuffer
      'i32* IntBuffer
      'i64* LongBuffer
      'float* FloatBuffer
      'double* DoubleBuffer
      ;; TODO: typed callbacks
      'fn Callback
      'callback Callback})

(defn descriptor
  "Get internal name type descriptor for t"
  [t]
  (cond
   (class? t) (Type/getDescriptor t)
   (fn? t) (Type/getDescriptor (t))))

;;; ***************************************************************************
;;;
;;; -----===== Functions =====-----
;;;
;;; ***************************************************************************

(defn make-native-func
  "Create a static method stub for a native function"
  [#^ClassVisitor cv fndesc]
  (let [name (:name fndesc)
        rettype  (type-map (:rettype fndesc))
        argtypes (map type-map (:argtypes fndesc))
        opcodes (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC Opcodes/ACC_NATIVE)
        desc (str "(" (apply str (map descriptor argtypes))
                  ")" (descriptor rettype))]
    (-> cv
        (.visitMethod (int opcodes) name desc nil nil)
        (.visitEnd))))

(defn make-native-lib-stub
  "Create the class needed for JNA direct mapping."
  [lib fn-descriptors & options]
  (let [defaults {:pkg (.replaceAll (str (ns-name *ns*)) "-" "_")
                  :name lib}
        opts (merge defaults (apply array-map options))
        {:keys [pkg name]} opts]
    (let [#^ClassVisitor cv (ClassWriter. 0)]
      (.visit cv Opcodes/V1_5 Opcodes/ACC_PUBLIC
              (.replaceAll (str pkg \/ name) "\\." "/")
              nil "java/lang/Object" (make-array String 0))
      ;; Class static block, must call Native.register here
      (doto #^MethodVisitor (.visitMethod cv Opcodes/ACC_STATIC
                                          "<clinit>" "()V" nil nil)
        (.visitCode)
        (.visitLdcInsn (if (string? lib) lib (str lib)))
        (.visitMethodInsn Opcodes/INVOKESTATIC "com/sun/jna/Native"
                          "register" "(Ljava/lang/String;)V")
        (.visitInsn Opcodes/RETURN)
        (.visitMaxs 1 0)
        (.visitEnd))
      ;; Default constructor, just delegate to Object
      (doto #^MethodVisitor (.visitMethod cv Opcodes/ACC_PUBLIC
                                          "<init>" "()V" nil nil)
        (.visitCode)
        (.visitVarInsn Opcodes/ALOAD 0)
        (.visitMethodInsn Opcodes/INVOKESPECIAL
                          "java/lang/Object" "<init>" "()V")
        (.visitInsn Opcodes/RETURN)
        (.visitMaxs 1 1)
        (.visitEnd))
      ;; Native function stubs
      (doseq [fndesc fn-descriptors]
        (make-native-func cv fndesc))
      ;; All done
      (.visitEnd cv)
      (.toByteArray cv))))

;;; ***************************************************************************
;;;
;;; -----===== Structs =====-----
;;;
;;; ***************************************************************************

(defn make-native-struct
  "Creates jna classes for representing a C struct
  that may be passed by value or reference.
  Returns a vector with 3 items; the bytecode for
  the specified struct as well as bytecode for inner
  classes representing the ByValue and ByReference
  versions of the struct respectively."
  [struct-spec]
  (let [#^ClassVisitor cv (ClassWriter. 0)
        replace-dots (fn [#^String s] (.replaceAll s "\\." "/"))
        internal-name (replace-dots (:classname struct-spec))
        inner-name (fn [t] (str internal-name "$" t))
        inner (fn [t]
                (let [#^ClassVisitor cv (ClassWriter. 0)
                      iname (inner-name t)]
                  (.visit cv Opcodes/V1_5
                          (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                          iname nil internal-name
                          (into-array
                           String [(str "com/sun/jna/Structure$" t)]))
                  (.visitOuterClass cv internal-name nil nil)
                  (doto #^MethodVisitor
                    (.visitMethod cv Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)
                    (.visitCode)
                    (.visitVarInsn Opcodes/ALOAD 0)
                    (.visitMethodInsn
                     Opcodes/INVOKESPECIAL internal-name "<init>" "()V")
                    (.visitInsn Opcodes/RETURN)
                    (.visitMaxs 1 1)
                    (.visitEnd))
                  (.visitEnd cv)
                  (.toByteArray cv)))]
    (.visit cv Opcodes/V1_5 Opcodes/ACC_PUBLIC internal-name
            nil "com/sun/jna/Structure" (make-array String 0))
    (.visitInnerClass cv (inner-name 'ByValue) internal-name
                      "ByValue" (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC))
    (.visitInnerClass cv (inner-name 'ByReference) internal-name
                      "ByReference"
                      (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC))
    (doto #^MethodVisitor
      (.visitMethod cv Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)
      (.visitCode)
      (.visitVarInsn Opcodes/ALOAD 0)
      (.visitMethodInsn
       Opcodes/INVOKESPECIAL "com/sun/jna/Structure" "<init>" "()V")
      (.visitInsn Opcodes/RETURN)
      (.visitMaxs 1 1)
      (.visitEnd))
    (doseq [field (:fields struct-spec)]
      (let [{nm :name
             type :type} field]
        (.visitEnd (.visitField cv Opcodes/ACC_PUBLIC (name nm)
                                (descriptor (type-map type)) nil nil))))
    (.visitEnd cv)
    [(.toByteArray cv) (inner 'ByValue) (inner 'ByReference)]))

(defn make-struct-constructor-stubs
  "Creates stub functions for struct constructors."
  [lib]
  (let [ns (ns-name *ns*)
        msg (str "Must call loadlib-" (:lib lib) " before this function")]
    (for [sspec (:structs lib)]
      (let [valname (symbol (str (:name sspec) "-byval"))
            refname (symbol (str (:name sspec) "-byref"))]
        `(do
           (defn ~valname
             ~(str "Creates a new structure object of type\n  "
                   (:name sspec) " that can be passed by value\n "
                   "between jvm and native code.")
             [] (throw (Exception. ~msg)))
           (defn ~refname
             ~(str "Creates a new structure object of type\n  "
                   (:name sspec) " that can be passed by reference\n "
                   "between jvm and native code.")
             [] (throw (Exception. ~msg))))))))

(defn make-struct-constructor
  "Creates code for creating a constructor
  function for a given struct."
  [sspec]
  (let [ns (ns-name *ns*)
        valname (symbol (str (:name sspec) "-byval"))
        refname (symbol (str (:name sspec) "-byref"))
        v (gensym)
        code (fn [name jname]
               `(eval
                 ~(list 'quote
                        `(let [~v (ns-resolve '~ns '~name)]
                           (.bindRoot
                            ~v
                            (fn []
                              (~(symbol (str (:classname sspec)
                                             "$" jname ".")))))))))]
    `(do
       ~(code valname 'ByValue)
       ~(code refname 'ByReference))))

;;; ***************************************************************************
;;;
;;; -----===== Callbacks =====-----
;;;
;;; ***************************************************************************

(defn make-callback-interface
  "Creates a java interface for a C callback specification."
  [cbspec]
  (let [#^ClassVisitor cv (ClassWriter. 0)]
    (.visit cv Opcodes/V1_5
            (+ Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT Opcodes/ACC_INTERFACE)
            (.replaceAll (:classname cbspec) "\\." "/") nil "java/lang/Object"
            (into-array String ["com/sun/jna/Callback"]))
    (let [rettype  (type-map (:rettype cbspec))
          argtypes (map type-map (:argtypes cbspec))
          opcodes (+ Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT)
          desc (str "(" (apply str (map descriptor argtypes))
                    ")" (descriptor rettype))]
      (-> cv
          (.visitMethod (int opcodes) "invoke" desc nil nil)
          (.visitEnd)))
    (.visitEnd cv)
    (.toByteArray cv)))

(defn make-callback-constructor-stubs
  "Creates stub functions for callback constructors."
  [lib]
  (let [ns (ns-name *ns*)
        msg (str "Must call loadlib-" (:lib lib) " before this function")]
    (for [cbspec (:cbs lib)]
      (let [name (symbol (str "make-" (:name cbspec)))]
        `(defn ~name
           ~(str "Creates a new callback proxy object of type\n  "
                 (:name cbspec) " that will delegate to the supplied clojure\n"
                 "  function when it is called from native code.\n"
                 "  The clojure function must take " (count (:argtypes cbspec))
                 " arguments\n  and return an object"
                 " of type " (type-map (:rettype cbspec)))
           [~'f] (throw (Exception. ~msg)))))))

(defn make-callback-constructor
  "Creates code for creating a constructor
  function for a given callback."
  [cbspec]
  (let [args (take (count (:argtypes cbspec)) (repeatedly gensym))
        ns (ns-name *ns*)
        name (symbol (str "make-" (:name cbspec)))
        v (gensym)]
    `(eval
      ~(list 'quote
             `(let [~v (ns-resolve '~ns '~name)]
                (.bindRoot
                 ~v
                 (fn [~'f]
                   (proxy [~(symbol (:classname cbspec))] []
                     (~'invoke ~(vec args)
                               (~'f ~@args))))))))))

;;; ***************************************************************************
;;;
;;; -----===== Parsing =====-----
;;;
;;; ***************************************************************************

(defn check-type
  [t]
  (when (not-any? #(= t %) (keys type-map))
    (throw (Exception. (str "Unknown type: " t))))
  t)

(defn parse-functions
  [fns]
  (let [third (fn [coll] (first (next (next coll))))
        drop-until (fn [pred coll] (drop-while #(not (pred %)) coll))]
    (for [fdef fns]
      (if-not (list? fdef)
        (throw (Exception. (str "invalid function description: " fdef)))
        (let [name (first fdef)
              doc (when (string? (second fdef)) (second fdef))
              cljname (cond (symbol? (second fdef)) (second fdef)
                            (and (string? (second fdef))
                                 (symbol? (third fdef))) (third fdef)
                            :else name)
              argvec (first (drop-until #(vector? %) fdef))
              rettype (second (drop-until #(vector? %) fdef))]
          (when-not (symbol? name)
            (throw (Exception.
                    (str "expected symbol as function name: " fdef))))
          (when-not (vector? argvec)
            (throw (Exception. (str "argument vector missing: " fdef))))
          ;; Can't support Buffers as return types since there is no way
          ;; of knowing the size of the returned memory block.
          ;; User must manually use the .getByteBuffer of the Pointer class.
          (when (and rettype
                     (.endsWith (str rettype) "*")
                     (not= 'void* rettype))
            (throw (Exception.
                    (str
                     "typed pointers are not supported as return types: "
                     fdef))))
          {:name (str name)
           :rettype (check-type (or rettype 'void))
           :argtypes (vec (for [a argvec] (check-type a)))
           :doc doc
           :cljname cljname})))))

(defn parse-callbacks
  [cbs]
  (for [cb cbs]
    (let [name (first cb)
          argtypes (second cb)
          rettype (first (next (next cb)))
          except #(throw (Exception. (str "malformed callback spec: " cb)))]
      (when-not name (except))
      (when-not (and argtypes (vector? argtypes)) (except))
      {:name name
       :argtypes argtypes
       :rettype (or rettype 'void)
       :classname (.replaceAll
                   (str (ns-name *ns*) \. "callback_" (UUID/randomUUID))
                   "-" "_")})))

;; TODO: keep track of user defined types
;; so that structs may refer to each other
;; or even themselves
;; Mapping between struct name symbols and
;; generated classnames?
(defn parse-structs
  [structs]
  (for [s structs]
    (do
      (when-not (symbol? (first s))
        (throw (Exception. (str "Malformed struct spec: " s
                                " name must be a symbol."))))
      (when-not (even? (count (next s)))
        (throw (Exception. (str "Malformed struct spec: " s
                                " uneven field declarations."))))
      (let [name (first s)
            fields (apply array-map (next s))]
        {:name name
         :classname (.replaceAll
                     (str (ns-name *ns*) \. "struct_" (UUID/randomUUID))
                     "-" "_")
         :fields (for [f fields]
                   {:name (key f)
                    :type (val f)})}))))

(defn parse
  "Parses input to defclib and returns a library specification map"
  [lib & body]
  (when-not (symbol? lib)
    (throw (Exception. "lib must be a symbol"))) ;; overkill?
  (let [when-key (fn [k f]
                   (when-let [stuff (some #(when (= k (first %))
                                             (next %))
                                          body)]
                     (f stuff)))
        callbacks (when-key :callbacks parse-callbacks)
        functions (when-key :functions parse-functions)
        structs (when-key :structs parse-structs)]
    {:lib lib
     :cbs callbacks
     :fns functions
     :structs structs
     :pkg (symbol (.replaceAll (str (ns-name *ns*)) "-" "_"))
     :classname (symbol (.replaceAll
                         (str "lib_" (UUID/randomUUID)) "-" "_"))}))

(defn argnames
  "Create unique names for a seq of argument types"
  [argtypes]
  (for [[i t] (indexed argtypes)]
    (symbol (str t i))))

;;; ***************************************************************************
;;;
;;; -----===== Initialization =====-----
;;;
;;; ***************************************************************************

;; TODO: make this return a run once only function
;; the LinkageErrors and the performance overhead are annoying
(defn loadlib-fn
  "Creates a function body that will load a native library
  and replace all the mapped function stubs with real versions."
  [lib]
  (let [clsname (str (:pkg lib) \. (:classname lib))]
    `(do
       ;; Try to load the native library before creating and
       ;; loading the JNA class because we want to discover the
       ;; error of not finding the library file here rather than
       ;; in the static class initializer.
       (Native/loadLibrary ~(str (:lib lib)) Library)
       ;; Structs
       ~@(for [sspec (:structs lib)]
           `(let [[main# val# ref#] (make-native-struct '~sspec)]
              (load-code ~(:classname sspec) main#)
              (load-code ~(str (:classname sspec) "$ByValue") val#)
              (load-code ~(str (:classname sspec) "$ByReference") ref#)
              ~(make-struct-constructor sspec)))
       ;; Callback interfaces and constructors
       ~@(for [cbspec (:cbs lib)]
           `(do
              (load-code ~(:classname cbspec)
                         (make-callback-interface '~cbspec))
              ~(make-callback-constructor cbspec)))
       ;; Main glue class
       (load-code ~clsname
                  (make-native-lib-stub
                   ~(str (:lib lib))
                   '~(:fns lib)
                   :name '~(:classname lib)
                   :pkg '~(:pkg lib)))
       ;; Rebinding of function var roots
       ;; TODO: move to own function like the others
       ~@(for [fdef (:fns lib)]
           (let [native (:name fdef)
                 name (:cljname fdef)
                 args (vec (argnames (:argtypes fdef)))
                 ns (ns-name *ns*)
                 v (gensym)]
             `(eval
               ~(list 'quote
                      (list 'let [v `(ns-resolve '~ns '~name)]
                            `(.bindRoot
                              ~v
                              ~(list `fn args
                                     (list* (symbol (str clsname \/ native))
                                            args)))))))))))

;; TODO: move this up to the other function stuff and rename it
(defn make-clj-stubs
  "Creates stub functions for linking against before the native
  library has been loaded."
  [lib]
  (for [fdef (:fns lib)]
    (let [m (if (:doc fdef)
              {:doc (:doc fdef)}
              {})
          m (if (not= (:rettype fdef) 'void)
              (assoc m :tag (type-map (:rettype fdef)))
              m)
          m (assoc m :arglists (list 'quote (list (:argtypes fdef))))
          n (:cljname fdef)
          args (argnames (:argtypes fdef))
          msg (str "Must call loadlib-" (:lib lib) " before this function")]
      (list 'def (with-meta n m)
            (list `fn (vec args) `(throw (Exception. ~msg)))))))

;;; ***************************************************************************
;;;
;;; -----===== Public Interface =====-----
;;;
;;; ***************************************************************************

(defmacro defclib
  "Create C library bindings.
  lib is a symbol naming the native library to link eg. 'c' for
  linking against the standard C runtime.
  body is any number of function descriptions of the form:

  (name docstring? clojure-name? [argtypes*] returntype?)

  Where name must match the name of the C function. The only
  other required component is the vector containing the argument
  types, even if the function takes no arguments. If the type
  of the return value is left out, void is assumed.
  A clojure function mapped to a C function that returns void
  will always return nil.
  clojure-name can be used to give the function a different name
  within the clojure runtime, useful eg. for getting rid of annoying
  prefixes used by C libraries to ensure unique names.

  This macro will create clojure functions in the current
  namespace corresponding to the imported C functions.
  A function called loadlib-libname will also be created where
  libname is the name of the native library. This function
  dynamically loads the native library and must be called at
  runtime (eg. at the top of -main) before using any of the
  mapped functions."
  [lib & body]
  (let [lib (apply parse lib body)]
    `(do
       (defn ~(symbol (str "loadlib-" (:lib lib)))
         ~(str "Loads the native library " (:lib lib)
               "\n  and rebinds the var roots of all it's mapped"
               "\n  functions to call their native counterparts.")
         [] ~(loadlib-fn lib))
       ~@(make-struct-constructor-stubs lib)
       ~@(make-callback-constructor-stubs lib)
       ~@(make-clj-stubs lib))))

(comment

  (use ['clj-native.core :only ['defclib]])

  (defclib
    m
    (:functions
     (sin [double] double)
     (cos [double] double)))

  (loadlib-m)

  (sin 1) ;; => 0.8414709848078965

  ;; Typed pointers are represented as nio buffers.
  ;; It's also possible to use java arrays with jna but I chose
  ;; nio buffers because they are faster (less copying) and
  ;; safer (direct buffers are not moved by GC so it's safe for
  ;; native code to hold on to their pointers).

  (defclib
    c
    (:functions
     (malloc [size_t] void*)
     (free [void*])
     (memset [byte* int size_t] void*)))

  (loadlib-c)

  (def mem (malloc 100))
  (def view (.getByteBuffer mem 0 100))
  (memset view 10 100)
  (.get view 20) ;; => 10
  (def int-view (.asIntBuffer view)) ;; 25 ints (100/4)
  (memset view 1 100)
  (.get int-view 20) ;; => 16843009 (four bytes, each with their lsb set)
  (free mem) ;; => nil
  (.get int-view 0) ;; => Undefined. Don't use freed memory ;)

)
