# Bigme daemon API is reflectively reached on xrz firmware. Keep our InkController
# implementations and their reflection targets stable across minified consumers.
-keep class com.inksdk.ink.** { *; }
