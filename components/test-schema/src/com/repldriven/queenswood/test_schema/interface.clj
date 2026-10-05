(ns com.repldriven.queenswood.test-schema.interface
  "A Pet record, for exercising FDB itself rather than any domain.

  `components/fdb` needs a record type to save, scan and query. Using a
  banking record would couple the lowest-level infrastructure brick to
  `components/schema` and the domain it encodes; this keeps that boundary
  clean, and keeps the fixture free to change for FDB's reasons alone."
  (:require
    [com.repldriven.queenswood.test_schemas.pets :as pets]

    [protojure.protobuf :as proto])
  (:import
    (com.repldriven.queenswood.test_schemas.pets PetProto$Owner
                                                 PetProto$Pet
                                                 PetProto$Toy)))

(defn pb->Pet
  "Parse Pet protobuf bytes into a map, without `:collar` when unset."
  [input]
  (let [pet (pets/pb->Pet input)]
    (cond-> pet
            (nil? (:collar pet))
            (dissoc :collar))))
(defn Pet->pb [m] (proto/->pb (pets/new-Pet m)))
(defn Pet->java [m] (PetProto$Pet/parseFrom (Pet->pb m)))

(def pb->Owner pets/pb->Owner)
(defn Owner->pb [m] (proto/->pb (pets/new-Owner m)))
(defn Owner->java [m] (PetProto$Owner/parseFrom (Owner->pb m)))

(def pb->Toy pets/pb->Toy)
(defn Toy->pb [m] (proto/->pb (pets/new-Toy m)))
(defn Toy->java [m] (PetProto$Toy/parseFrom (Toy->pb m)))
