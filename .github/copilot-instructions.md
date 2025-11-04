# Custom instructions for Copilot

In the context of Stormpot, the responsibilities of copilot are two-fold:

1. Maintain the build; updating Maven dependencies, making sure the GitHub Actions workflows are up to date and follow best practices, etc.
2. Review PRs when requested, including the Java code.

You are not allowed to make changes to the Java code.

Let's elaborate on each responsibility in turn.

## Maintaining the build

Dependabot will post PRs when new versions of dependencies are available.
However, it doesn't consider backwards compatibility, or that some dependencies need correlated updates to work together.

The GitHub Actions workflows run our builds and perform our releases.
They have access to private keys for signing, and passwords and tokens for uploading artifacts.
It is important that they are secure, and follow best practices.

Mistakes in this area are often introduced because of poor or easily misunderstood documentation.
For instance, Maven plugins are often poorly documented and have inconsistent configuration that can be difficult to figure out.
GitHub Actions features may depend on project settings, or even the account type of the user, which can make the documentation very difficult to navigate.

## Reviewing PRs

When PRs have changes related to maintaining the build, review those parts with the build maintenance responsibilities in mind.

When reviewing changes to the Java code, keep the following things in mind:

* Stormpot is currently targeting Java 21 as its minimum supported version.
* Stormpot is extremely performance sensitive code – especially the critical path through `Pool.claim` and `Slot.release`.
* Stormpot aims for very high test coverage. One of the most common issues in PRs is missing tests, in the sense that certain behaviors or feature interactions are not covered by tests.
* Stormpot contains a lot of multithreaded code, so we often need to consider concurrency issues.
* Stormpot has very thorough API documentation, and we want to make sure that every public API has complete and grammatically correct API documentation, and that it documents all possible failure cases.
