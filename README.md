# flux

This is a fork of Matt Mitchell's Flux - a Clojure based Solr client + Criteria DSL to make SOLR queries bit sweeter. Current Apache Solr version support is `4.9.0`.

## Installation (Leiningen)

To include the Flux library, add the following to your `:dependencies`:

    [com.codesignals/flux "0.6.0"]

## Usage

###Http

```clojure
(require '[flux.http :as http])

(def conn (http/create "http://localhost:8983/solr" :collection1))
```

###Cloud
Create a connection to SolrCloud using one zk host:

```clojure
(require '[flux.cloud :as cloud]
          [flux.collections :as cloud-api)

(def conn (cloud/create "zk1:2181"))
```

Create a connection to SolrCloud using multiple zk hosts (for failover):

```clojure
(def conn (cloud/create "zk1:2181,zk2:2181,zk3:2181"))
```

Create a connection to SolrCloud using multiple zk hosts (for failover) and a chroot:

```clojure
(def conn (cloud/create "zk1:2181,zk2:2181,zk3:2181/mysolrchroot"))
```

Create a connection to SolrCloud using zk and specify a default collection

```clojure
(def conn (cloud/create "zk1:2181,zk2:2181,zk3:2181/mysolrchroot" "name-of-collection"))
```

Create a new SolrCloud collection with 4 shards:

```clojure
(cloud-api/create-collection conn "my-solr-collection" 4)
```

Create a new SolrCloud collection with 4 shards and replication factor of 2:

```clojure
(cloud-api/create-collection conn "my-solr-collection" 4 2)
```

Create a new SolrCloud collection with 4 shards and replication factor of 2 and additional parameters:

```clojure
(cloud-api/create-collection conn "my-solr-collection" 4 2 { "collection.configName" "schemaless"
                                                  "router.name" "implicit"
                                                  "shards" "x,y,z,p"
                                                  "maxShardsPerNode" 10})
```

The SolrCloud connection is thread-safe and it is recommended that you create just one
and re-use it across all requests. 

Remember to shutdown the connection on exit:

```clojure
(flux/with-connection conn (flux/shutdown))
```

Delete a SolrCloud collection:

```clojure
(cloud-api/delete-collection conn "my-solr-collection")
```

Get a list of active replicas of a collection:

```clojure
(filter active? (all-replicas conn "collection1"))
```

Get a list of not-active replicas (either down or recovering) of a collection:

```clojure
(filter not-active? (all-replicas conn "collection1"))
```

Get a list of down replicas of a collection:

```clojure
(filter down? (all-replicas conn "collection1"))
```

Get a list of recovering replicas of a collection:

```clojure
(filter recovering? (all-replicas conn "collection1"))
```

Get a list of replicas of a collection hosted by host/port:

```clojure
(filter (fn [x] (hosted-by? x "127.0.1.1:8983")) (all-replicas conn "collection1"))
```

Get a list of leaders of a particular collection:

```clojure
(filter leader? (all-replicas conn "collection1"))
```

Wait for all replicas of a given collection hosted by a particular host/port to be in 'active' state:

```clojure
(wait-until-active conn "my-solr-collection" "host1:8983")
```

###Embedded

```clojure
(require '[flux.embedded :as embedded])

(def cc (embedded/create-core-container "path/to/solr-home" "path/to/solr.xml"))
```

####Core auto-discovery
Flux also supports `core.properties`. Just give `create-core` the solr-home path as the only argument.

  Note: It's important to call the `load` method on the resulting `CoreContainer` instance:

```clojure
(def cc (doto (embedded/create-core-container "path/to/solr-home")
              (.load))
```

Now create the embedded server instance:

```clojure
(def conn (embedded/create cc :collection1))
```

###Client
Once a connection as been created, use the `with-connection` macro to wrap client calls:

```clojure
(require '[flux.core :as flux]
          [flux.query :as q])

(flux/with-connection conn
    (flux/add [{:id 1} {:id 2}])
    (flux/commit)
    (flux/query "*:*")
    ;; Or set the path and/or method:
    (flux/request
      (q/create-query-request :post "/docs" {:q "etc"}))
```

### Criteria DSL
As it's quite cumbersome to define filters using raw Solr syntax following is a neat criteria DSL to make things a bit smoother.

```clojure
(use 'flux.criteria)

;; fq=make:Mercedes*

(with-filter
  (is :make "Mercedes*"))

;; fq={!tag=make}category:car/Mercedes/Sprinter

(with-filter
  (is (!tag :category "make") "car/Mercedes/Sprinter"))

;; fq=category:car/Mercedes/Sprinter AND build_year:2010

(with-filter
  (is :category "car/Mercedes/Sprinter")
  (is :build_year 2010))

;; this is equivalent to:

(with-filter
  (and
    (is :category "car/Mercedes/Sprinter")
    (is :build_year 2010)))

;; and can be shortened even further (this kind of "shortcut" applies to all functions listed below):

(with-filter
  (is {:category  "car/Mercedes/Sprinter" 
       :build_year 2010}))

;; fq=(-technical_condition:damaged) OR ((price:[* TO 5000]) AND (build_year:[* TO 2005]))

(with-filter
  (or
    (is-not :technical_condition "damaged")
    (<= {:price 5000 :build_year 2005})))

;; fq=make:[audi mercedes]

(with-filter
   (any-of :make ["audi" "mercedes"]))

;; fq=-make:[zaporożec wołga]

(with-filter
   (none-of :make ["zaporożec" "wołga"]))

;; fq=(description:"nówka sztuka") OR (price:[5000 TO 15000] AND currency:PLN))

(with-filter
   (or
     (has :description "nówka sztuka")
     (and
        (between :price [5000 15000])
        (is :currency "PLN"))))

```
Same story with facets:

```clojure
;; facet.field=popularity&facet.field=category

(with-facets
  (fields :popularity :category))
	
;; facet.field={!ex=dt}popularity

(with-facets
  (fields (!ex :popularity "dt")))

;; for fields all options are automaticaly prefixed with "facet.", so :limit becomes facet.limit
;; for pivots all options are automaticaly prefixed with "facet.pivot.", so :mincount becomes facet.pivot.mincount

;; facet.field=category&facet.field=popularity&facet.field.limit=100&facetfield.offset=12&
;; facet.pivot=category,price&facet.pivot=popularity&facet.pivot.limit=5&facet.pivot.mincount=20

(with-facets
  (fields {:limit 100 :offset 12} :category :popularity)
  (pivots {:limit 5 :mincount 20} [:category :price] [:popularity]))

;; facet.query=category:car AND build_year:2000

(with-facets
    (query
      (is :category "car")
   	  (is :build_year 2000)))
```
Last but not least are options: rows returned, requested page and sorting criteria:

```clojure
(with-options
  {:rows 200 :page 2 :sort "price ASC"})
```

Few notes regarding available options:
- pages are 0 indexed (so the first page is 0, next one 1 and so on...)
- default rows limit is 100
- default sorting is "score desc"

All these "with-" forms perfectly chain with each other, so it's pretty valid to combine them as following:

```clojure
(-> (with-filter  ...)  ;; fq=...
    (with-facets  ...)  ;; facets & pivots
    (with-options ...)) ;; rows=... & start=... & sort=...
```

Moreover to get advantage of SOLR caching you may try to split with-filter to several separate ones:

```clojure
(-> (with-filter ...)    ;; fq=...
    (with-filter ...))   ;; fq=...
```

Alright, fully functional sample at the end:

```clojure
(require '[flux.http :as http])
(require '[flux.core :as flux])
(require '[flux.criteria :as c])

(def conn (http/create "http://localhost:8983/solr" :collection1))

(flux/with-connection conn
  (flux/query "*:*"
              (-> (c/with-filter (c/is :category "car/BMW"))
                  (c/with-facets (c/fields :build_year))
                  (c/with-options {:page 2)))))
```


###javax.servlet/servlet-api and EmbeddedSolrServer

Unfortunately, EmbeddedSolrServer requires javax.servlet/servlet-api as an implicit dependency. Because of this, Flux adds this lib as a depedency.

  * http://wiki.apache.org/solr/Solrj#EmbeddedSolrServer
  * http://lucene.472066.n3.nabble.com/EmbeddedSolrServer-java-lang-NoClassDefFoundError-javax-servlet-ServletRequest-td483937.html

###Test
```shell
lein midje
```

## License

Copyright © 2013-2014 Matt Mitchell

Distributed under the Eclipse Public License, the same as Clojure.
