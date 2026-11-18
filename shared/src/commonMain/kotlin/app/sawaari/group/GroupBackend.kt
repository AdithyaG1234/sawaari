package app.sawaari.group

/**
 * The few Firebase calls group rides need, so the group logic in [GroupClient] can be shared.
 * On iPhone it is implemented in Swift with the Firebase iOS SDK (iosApp/Sawaari/FirebaseBridge.swift).
 *
 * Values cross as JSON text, like the Firebase REST API: an object, array, string, number,
 * boolean, or null for "no value". Callbacks may arrive on any thread.
 */
interface GroupBackend {
    /** False when this build has no Firebase config, so group rides can't work. */
    fun isConfigured(): Boolean

    /** The signed-in anonymous user, or null if not signed in yet. */
    fun currentUid(): String?

    /** Sign in anonymously (or keep the current user). Calls back with the uid, or an error message. */
    fun signIn(callback: (uid: String?, error: String?) -> Unit)

    /** Read [path] once. */
    fun read(path: String, callback: (json: String?, error: String?) -> Unit)

    /** Write [json] at [path]; null removes it. */
    fun write(path: String, json: String?, callback: (error: String?) -> Unit)

    /** Write several children of [path] at once: [json] is an object of relative paths to values (null removes). */
    fun update(path: String, json: String, callback: (error: String?) -> Unit)

    /** Add [json] under a new unique key in [path]. Calls back with the new key. */
    fun push(path: String, json: String, callback: (key: String?, error: String?) -> Unit)

    /**
     * Watch [path] and get its value now and on every change. With [orderByChild] and [limitToLast] > 0,
     * only the last [limitToLast] children ordered by that child are sent. Returns a handle for [stopObserving].
     */
    fun observe(
        path: String,
        orderByChild: String?,
        limitToLast: Int,
        onValue: (json: String?) -> Unit,
        onError: (message: String) -> Unit,
    ): Long

    fun stopObserving(handle: Long)
}

/** Small saved settings (name, current group code). NSUserDefaults on iPhone. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}
