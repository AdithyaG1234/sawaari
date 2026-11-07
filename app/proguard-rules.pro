# MapLibre ships its own consumer rules; add app-specific rules here.

# GraphHopper creates parts of its routing setup by name and reads stored graph
# properties, so keep it whole. Its server-only dependencies are absent on Android.
-keep class com.graphhopper.** { *; }
-keep class com.carrotsearch.hppc.** { *; }
-dontwarn com.graphhopper.**
-dontwarn org.codehaus.janino.**
-dontwarn org.codehaus.commons.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.locationtech.jts.**
-dontwarn org.apache.xmlgraphics.**
-dontwarn org.apache.commons.**
-dontwarn org.slf4j.**
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.openstreetmap.**
-dontwarn crosby.binary.**

# Firebase Realtime Database maps snapshots by reflection only for POJOs; we read
# values field by field, so nothing extra to keep. MapLibre ships its own rules.
