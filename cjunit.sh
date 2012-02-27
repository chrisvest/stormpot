mvn clean test
java -cp lib/cjunit-all.jar:target/classes/:target/test-classes/ de.fzi.cjunit.ConcurrentJUnit `find src/test|grep Test|sed 's/src\/test\/java\///'|sed 's/\//./g'|sed 's/.java//'`
