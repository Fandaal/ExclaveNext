-repackageclasses ''
-allowaccessmodification

-keep class io.nekohasekai.sagernet.** { *;}
-keep class com.github.exclavenetwork.exclave.core.app.observatory.** { *; }

# SnakeYaml
-keep class org.yaml.snakeyaml.** { *; }

# MaxMind geoip2 / maxmind-db: DatabaseReader maps rows onto model classes
# (Metadata, CountryResponse, AsnResponse, ...) through RUNTIME REFLECTION on
# constructors annotated @MaxMindDbConstructor. Without these rules R8 folds
# those classes into unrelated merge targets (kotlin.TuplesKt) and strips the
# annotation attributes, so every .mmdb is rejected with "No constructor on
# class kotlin.TuplesKt with the MaxMindDbConstructor annotation was found".
# First seen in the 0.17.58.1 release build — debug builds never run R8.
-keep class com.maxmind.db.** { *; }
-keep class com.maxmind.geoip2.model.** { *; }
-keep class com.maxmind.geoip2.record.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

-dontobfuscate
-keepattributes SourceFile

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport