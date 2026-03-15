# SnakeYAML's desktop bean introspection path references java.beans.*, which is
# absent on Android and not used by AndroidClaw's SKILL/frontmatter parsing path.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
