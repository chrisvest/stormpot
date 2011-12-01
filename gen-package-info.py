#!/usr/bin/env python

from codecs import open
from markdown2 import Markdown

markdown_options = {
  'code-color':{'classprefix':'cc'},
  'header-ids':'',
  'footnotes':''
}

template = """
/**
%s
*/
package stormpot;
"""

with open("package-info.md", "r", "utf-8") as f:
  md = f.read()
markdown = Markdown(extras=markdown_options)
java = template % markdown.convert(md)
with open("src/main/java/stormpot/package-info.java", "w", "utf-8") as f:
  f.write(java)
