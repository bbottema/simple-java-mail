Compiles simplejavamail into an Apache Karaf feature. 

For project-wide mechanisms such as optional module loading and build-generated metadata, see [../../PROJECT_MECHANISMS_CATALOGUE.md](../../PROJECT_MECHANISMS_CATALOGUE.md).

Usage in Karaf simılar to: 
```
karaf@root()> repo-add mvn:org.simplejavamail/simplejavamail-karaf-feature/<VERSION>/xml/features
karaf@root()> feature:install simplejavamail-karaf-feature
```

