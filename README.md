[![Clojars Project](https://img.shields.io/clojars/v/bsless/more.async.svg)](https://clojars.org/bsless/more.async)
[![cljdoc badge](https://cljdoc.org/badge/bsless/more.async)](https://cljdoc.org/d/bsless/more.async/CURRENT)
[![CircleCI](https://circleci.com/gh/bsless/more.async/tree/master.svg?style=shield)](https://circleci.com/gh/bsless/more.async/tree/master)

# more.async

More core.async idioms and abstractions.

It is important to have the proper set of abstractions and idioms for problem solving and building systems. It allows the programmer to focus more on the system and its language and less on boilerplate, ceremony and logistics.

This library tries to enrich the language of core.async by providing the useful idioms which often repeat across code bases in some variation, among which are:

- producing/consuming to channels at the edges of a system
- dispatching/routing channels
- batching
- waiting for a group of tasks

## Extra Batteries

### Naming conventions

- `!`/`!!`: names ending in `!` are for async operations which happen in
  go-blocks. Names ending in `!!` are blocking and run on the calling
  thread
- `-call`: Names ending in `-call` are for functions taking other
  functions as arguments. An equivalent name without the `-call` suffix
  is a wrapper macro which wraps a body in a function. see `future` and
  `future-call` as an example.

### Producing and Consuming

The abstraction of a process sitting at the edge of a pipeline and
producing to or consuming from a channel is quite common. It is how we
connect our pipelines to the rest of the world.

`more.async` provides the following abstractions while adhering to
Clojure's core and core.async naming conventions:

| Blocking? | Producing        | Consuming                |
| :--       | :--              | :--                      |
| no        | `produce-call!`  | `consume-call!`          |
| no        | `produce!`       | `consume!`               |
| yes       | `produce-call!!` | `consume-call!!`         |
| yes       | `produce!!`      | `consume!!`              |
| no        |                  | `consume-checked-call!`  |
| no        |                  | `consume-checked!`       |
| yes       |                  | `consume-checked-call!!` |
| yes       |                  | `consume-checked!!`      |

- `produce-call!`: takes a channel and a function, repeatedly produces
  to the channel the results of applying the function as long as it
  returns a non `nil` value and the channel is open.
- `produce!`: like `produce-call!` but takes a body which is evaluated
  every iteration and its result is put into the channel.
- `consume-call!`: takes a channel and a function, repeatedly consumes
  from the channel and applies the function to the consumed value until
  the channel closes.
- `consume!`: takes a channel, binding name and a body, repeatedly
  evaluates the body with the variable name bound to values consumed
  from the channel.
- `consume-checked!`: same as `consume!` but recurs only when the
  provided function returns a truth-y value.
  
### Waiting

Another common pattern is waiting for a group of tasks, represented as
returned channels (from threads or go blocks) to finish.

`wait!` and `wait!!` take a collection of channels and waits for them
all to deliver. `wait!` returns a channel, `wait!!` blocks the calling
thread.

`wait-group-call` takes a number n, function and cleanup function, will
call the function n times in a *thread* and wait for it to finish. Once
all calls are finished the cleanup function will be called once. Returns
a channel which closes after cleanup is done.

`wait-group` is a macro which wraps `wait-group-call` and allows writing
an execution body directly instead of passing functions around, for
example:

```clojure
(wait-group
 8
 (let [n (+ 1000 (rand-int 1000))]
   (Thread/sleep n)
   (println n))
 :finally
 (println "goodbye!"))
```

`:finally` is the delimiter used to separate the cleanup portion which will be executed only once.

### Batching

A proper batching process with timeout semantics is essential.

`batch!` and `batch!!` provide transducer-like semantics in non-blocking
and blocking contexts respectively, and pipeline-like semantics with
regards to connecting to other channels:

`batch!` takes:
- input channel
- output channel
- batch size
- timeout ms
- reducing function: `rf :: batch -> input -> batch`
- init function which provides the initial value (NOTE: this is a
  deviation from transducers semantics, don't get it wrong)
- an indicator should the output channel be closed if the input channel
  closes.
  
`batch!` is written to be very flexible and can even work with stateful accumulators:

```clojure
(defn ticker
  []
  (let [a (atom 0)]
    (fn []
      (swap! a inc))))

(def in (a/chan))
(def out (a/chan))
(def n 3)
(def f (ticker))
(produce-call! in f)
(a/thread
  (batch!! in out n 1000 (fn [^StringBuilder sb x] (.append sb x)) #(StringBuilder.) true))

(str (a/<!! out))
;; => "123"
(str (a/<!! out))
;; => "456"
```

### Routing

`split!` and `split?!` are similar to `core.async/split` but take an
input channel, a map of values to output channels, and a routing
function. Every value from the input channel is routed to the output
channel corresponding to the result of the routing function.

The difference between the versions is that `split?!` drops values which
can't be routed while `split!` throws.


### Errata

`periodically!` - invoke a function periodically (every timeout) and put
its results into returned channel. Essentially a timer channel.
`reductions!` - like `core/reductions` from input channel to output
channel. Produces an intermediate state only when consuming from output
channel. Useful for maintaining internal state while processing a
stream.

## Usage

### Dependency

Add the following dependency in your leiningen project:
```clojure
[bsless/more.async "0.0.4"]
```

### Require

```clojure
(require '[clojure.core.async :as a]
         '[clojure.more.async :as ma])
```

### Use

For example, with [kinsky's](https://github.com/pyr/kinsky) kafka client:

#### Channel Producer

```
(require '[kinsky.client :as client])

(defn make-consumer
  []
  (let [c (client/consumer {:bootstrap.servers "localhost:9092"
                            :group.id          "mygroup"}
                           (client/keyword-deserializer)
                           (client/edn-deserializer))]
    (client/subscribe! c "account")
    c))

(def msg-ch (a/chan))

(a/thread
  (let [consumer (make-consumer)]
    (ma/produce-blocking
     msg-ch
     (client/poll! consumer 100))
    (client/close!)))
```

#### Channel Consumer

```
(def out-ch (a/chan))

(let [p (client/producer {:bootstrap.servers "localhost:9092"}
                         (client/keyword-serializer)
                         (client/edn-serializer))
      topic "account"]
  (ma/consume out-ch v (client/send! p topic v)))
```

## Status

In active development. Might contain bugs. PRs welcome.

## License

Copyright © 2019-2020 Ben Sless

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
