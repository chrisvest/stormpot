
mvn clean install site
git co gh-pages
rm -fr site
cp -fr target/site .
git add -A site
git ci -m 'maven site update.'
git push
git co master
