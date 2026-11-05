# kotlinx.serialization models in :core:catalog / :core:download
-keepclassmembers class dev.ruchin.anvil.** {
    *** Companion;
}
-keepclasseswithmembers class dev.ruchin.anvil.** {
    kotlinx.serialization.KSerializer serializer(...);
}
