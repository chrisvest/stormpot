Stormpot
========

Stormpot is an object pooling library for Java. Use it to recycle objects that
are expensive to create. The library will take care of creating and destroying
your objects in the background.

 * Home page: http://chrisvest.github.io/stormpot/
 * Mailing list: http://groups.google.com/d/forum/stormpot

[![Build Status](https://travis-ci.org/chrisvest/stormpot.png)](https://travis-ci.org/chrisvest/stormpot)

Why choose Stormpot?
--------------------

... over, say, Commons-Pool? Good question! Both libraries are released under
the Apache 2.0 license, both have thorough documentation, both have high test
coverage (Stormpot has over 90% coverage) and both depend on nothing but Java
itself.

There are differences, though. Stormpot has a small invasive API, whereas
Commons-Pool has a richer and less intrusive API. The different slants in their
API design comes from differing focus. Stormpot is designed for performance
and a unified API for different implementations. This means that those who use
Stormpot can switch between implementations without worrying about
compatibility. Commons-Pool, on the other hand, have different APIs for
two different kinds of pools, so changing pool implementation might mean also
having to change the code that uses the pool. On the other hand, the various
pool implementations can make special features available that would otherwise
be inconvenient to expose through a generic API.

License
-------

Stormpot is licensed under the Apache 2.0 software license:
http://www.apache.org/licenses/LICENSE-2.0.html

