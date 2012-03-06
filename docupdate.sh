
mvn clean install site
git co gh-pages
rm -fr site
cp -fr target/site .

# fix jacoco resources
mv site/jacoco/.resources site/jacoco/resources
perl -e 's/\.resources/resources/g;' -pi $(find site/jacoco -name '*.html')

git add -A site
git ci -m 'maven site update.'
git push
git co master
