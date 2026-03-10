package com.example.sound

class NativeLib {

    /**
     * A native method that is implemented by the 'sound' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'sound' library on application startup.
        init {
            System.loadLibrary("sound")
        }
    }
}