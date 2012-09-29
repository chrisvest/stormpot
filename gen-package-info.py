#!/usr/bin/env python

from codecs import open
from markdown2 import Markdown

markdown_options = {
  'code-color':{'classprefix':'cc'},
  'footnotes':'',
  'toc':'toc'
}

template = """
// GENERATED FILE!
// Look at package-info.md in project root, and gen-package-info.py
/**
%s
*/
package stormpot;
"""

with open("package-info.md", "r", "utf-8") as f:
  md = f.read()
markdown = Markdown(extras=markdown_options)
html = markdown.convert(md)
java = template % html.replace(u'{toc}', html.toc_html)
with open("src/main/java/stormpot/package-info.java", "w", "utf-8") as f:
  f.write(java)
