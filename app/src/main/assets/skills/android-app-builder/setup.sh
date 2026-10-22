#!/bin/sh
# Makes a new Android app project from Blaze's template.
#   sh ~/skills/android-app-builder/setup.sh <dir> <package> "<App name>"
#   e.g. sh ~/skills/android-app-builder/setup.sh ~/code/habit-tracker com.joey.habits "Habit Tracker"
# Copies the template into <dir> (which may already be a cloned, empty repo), renames the
# package and app name, and puts .github/ and .gitignore in place.
set -e
DIR="$1"; PKG="$2"; NAME="$3"
[ -n "$DIR" ] && [ -n "$PKG" ] && [ -n "$NAME" ] || { echo "usage: setup.sh <dir> <package> \"<App name>\""; exit 1; }
echo "$PKG" | grep -Eq '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' || { echo "package must look like com.name.app (lowercase)"; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)/template"
mkdir -p "$DIR"
cp -R "$SRC/." "$DIR/"
cd "$DIR"
mkdir -p .github && rm -rf .github/workflows && mv github/workflows .github/ && rm -rf github
mv gitignore .gitignore
PKGDIR=$(echo "$PKG" | tr . /)
mkdir -p "app/src/main/java/$PKGDIR"
mv app/src/main/java/com/example/app/*.kt "app/src/main/java/$PKGDIR/"
rm -rf app/src/main/java/com/example
sed -i "s/com\.example\.app/$PKG/g" app/build.gradle.kts app/src/main/java/$PKGDIR/*.kt
# Android string resources: XML-escape, and backslash-escape quotes.
XML=$(printf '%s' "$NAME" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e "s/'/\\\\'/g" -e 's/"/\\"/g')
printf '<resources>\n    <string name="app_name">%s</string>\n</resources>\n' "$XML" > app/src/main/res/values/strings.xml
SAFE=$(printf '%s' "$NAME" | tr -cd 'A-Za-z0-9 _-' | tr -s ' ')
sed -i "s/rootProject.name = \"MyApp\"/rootProject.name = \"$SAFE\"/" settings.gradle.kts
chmod +x gradlew
echo "Ready: $DIR ($PKG, \"$NAME\")"
find . -path ./.git -prune -o -type f -print | sort
