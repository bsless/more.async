# Change Log

## [unreleased] 2021-02-04

- rename reductions* to selecting-reductions*
- add reductions pipeline
- Add round-robin!

## [0.0.7] - 2021-01-13

- BREAKING CHANGE: remove clojure name from all namespaces

## [0.0.5]

- add waiting conventions
- Rename all core function
- Fix bugs in batch
- Reductions api similar to transduce
- Add circleci integration

## [0.0.4] - 2020-04-05

### Changed

- produce/consume api rewrite.

### Added
- Out of order pipeline implementation.
- future-like api around produce/consume functions.

### Removed
- produce-bound-blocking

## [0.0.3] - 2019-12-14
### Added
- reductions: publishes reductions of inputs to output channel.
- mux macro: multiplex input channel into maps.
- documentation improvements

### WIP
- Kafka example

## [Unreleased] - 2019-11-08
### Added
- Control function wrappers

## [0.0.2-alpha] - 2019-11-04
### Version
- Bump for release

### Added
- Usage in README

## [Unreleased] - 2019-11-02
### Add
- merge! and fan!

## [Unreleased] - 2019-10-31
### Add
- Blocking implementations separate from threads creation.
- tests

### Refactor
- Update docstrings.
- Break bindings from go-loop to let inside go-loop.
- split blocking implementations from thread creation to allow threading model choice.

## [0.0.1-alpha] - 2019-10-29
### Added
- initial produce, consume and split implementation
