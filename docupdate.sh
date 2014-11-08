#!/bin/sh

mvn clean verify site
git co gh-pages
rm -fr site
cp -fr target/site .
cp -fr target/docs/* .

# fix jacoco resources
mv site/jacoco/.resources site/jacoco/resources
perl -e 's/\.resources/resources/g;' -pi $(find site/jacoco -name '*.html')

git add -A .
echo 'Site generated. Ready to commit and push.'
