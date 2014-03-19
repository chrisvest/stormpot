#!/usr/bin/env python

from codecs import open
from markdown2 import Markdown

markdown_options = {
  'code-color':{'classprefix':'cc'},
  'footnotes':'',
  'toc':'toc'
}

template = """/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
java = template % html.replace(u'<p>{toc}</p>', html.toc_html)
with open("src/main/java/stormpot/package-info.java", "w", "utf-8") as f:
  f.write(java)
