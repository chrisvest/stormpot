#!/usr/bin/env bash

echo -n '(building classpath... '
export CP=$(mvn --offline dependency:build-classpath|grep .m2)
echo 'done)'
java -cp target/classes:target/test-classes:$CP \
  com.google.caliper.Runner $*

