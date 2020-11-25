# more.async

More core.async idioms and abstractions.

It is important to have the proper set of abstractions and idioms for problem solving and building systems. It allows the programmer to focus more on the system and its language and less on boilerplate, ceremony and logistics.

This library tries to enrich the language of core.async by providing the useful idioms which often repeat across code bases in some variation, among which are:

- producing/consuming to channels at the edges of a system
- dispatching/routing channels
- batching
- waiting for a group of tasks

## Usage

### Dependency

Add the following dependency in your leiningen project:
```clojure
[bsless/more.async "0.0.2-alpha"]
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

(ma/produce-bound-blocking
 msg-ch
 #(client/poll! % 100)
 make-consumer
 client/close!)
```

#### Channel Consumer

```
(def out-ch (a/chan))

(let [p (client/producer {:bootstrap.servers "localhost:9092"}
                         (client/keyword-serializer)
                         (client/edn-serializer))
      topic "account"]
  (ma/consume out-ch #(client/send! p topic %)))
```

## License

Copyright © 2019 Ben Sless

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
