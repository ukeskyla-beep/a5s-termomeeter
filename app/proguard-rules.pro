# Room genereerib implementatsiooniklassid nime järgi — neid ei tohi ümber nimetada.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Meie andmeklassid liiguvad Compose'i olekus ja andmebaasi vahel.
-keep class ee.ukesk.a5s.data.db.** { *; }
