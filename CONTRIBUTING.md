
Contributing to Stormpot
========================

Stormpot is implemented with a fairly strict adherence to TDD, but that does not
mean that other people cannot join in and help. I might set a high bar on
quality and correctness, but don't let that discourage you. See it instead as a
challenge and perhaps an opportunity to learn.

The general procedure is to just fork the repository, make your changes and
then submit a pull request.

If you have any questions, then open an issue with the "question" label.
You can alternatively send me an email, if you have reasons to prefer that.

If you want to work on the web site or the documentation, then go right ahead.

If you want to work on the code, then there are a couple of things to note:

 * Make sure your pull request applies cleanly. I don't care if you do this by
   rebasing, or by proactively pulling and merging changes from upstream master.
 * To the furthest extent possible, make sure that you follow the strict TDD
   practice that Stormpot has been built with: Test for observable behaviour of
   the public API. Aim for 100% coverage. Test all failure modes. Document all
   aspects of features, including all failure modes.
 * Pull requests that make changes to existing functionality, needs to include
   prose that convinces me of the correctness of the new code. If the changes
   are trivial, then this will be easy. If the changes are non-trivial, then
   it will be necessary.
 * Follow the style conventions of the existing code. Indentation is two spaces.
   Line breaks are Unix style. Character encoding is UTF-8.
 * Stormpot uses Semantic Versioning, so be careful not to break backwards
   compatibility.

If you need inspiration on what to work on, then check out the issues that are
labelled with [help-wanted][1] or [easy][2], or maybe try to learn more about
the code by joining the discussion and helping research and answer
[questions][3].

[1]: https://github.com/chrisvest/stormpot/issues?labels=help-wanted&page=1&state=open
[2]: https://github.com/chrisvest/stormpot/issues?labels=easy&page=1&state=open
[3]: https://github.com/chrisvest/stormpot/issues?labels=question&page=1&state=open
