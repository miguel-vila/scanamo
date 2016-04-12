package com.gu.scanamo

import cats.data.{Streaming, ValidatedNel}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.model._

/**
  * Scanamo provides a simplified interface for reading and writing case classes to DynamoDB
  */
object Scanamo {

  def exec[A](client: AmazonDynamoDB)(op: ScanamoOps[A]) = op.foldMap(ScanamoInterpreters.id(client))

  /**
    * Puts a single item into a table
    *
    * {{{
    * >>> case class Farm(animals: List[String])
    * >>> case class Farmer(name: String, age: Long, farm: Farm)
    *
    * >>> import com.gu.scanamo.syntax._
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> val client = LocalDynamoDB.client()
    *
    * >>> LocalDynamoDB.withTable(client)("farmers")('name -> S) {
    * ...   Scanamo.put(client)("farmers")(Farmer("McDonald", 156L, Farm(List("sheep", "cow"))))
    * ...   Scanamo.get[Farmer](client)("farmers")('name -> "McDonald")
    * ... }
    * Some(Valid(Farmer(McDonald,156,Farm(List(sheep, cow)))))
    * }}}
    */
  def put[T: DynamoFormat](client: AmazonDynamoDB)(tableName: String)(item: T): PutItemResult =
    exec(client)(ScanamoFree.put(tableName)(item))

  /**
    * Gets a single item from a table by a unique key
    *
    * {{{
    * >>> case class Rabbit(name: String)
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> LocalDynamoDB.withTable(client)("rabbits")('name -> S) {
    * ...   Scanamo.putAll(client)("rabbits")((
    * ...   for { _ <- 0 until 100 } yield Rabbit(util.Random.nextString(500))).toList)
    * ...   Scanamo.scan[Rabbit](client)("rabbits").toList.size
    * ... }
    * 100
    * }}}
    */
  def putAll[T: DynamoFormat](client: AmazonDynamoDB)(tableName: String)(items: List[T]): List[BatchWriteItemResult] =
    exec(client)(ScanamoFree.putAll(tableName)(items))

  /**
    * {{{
    * >>> case class Farm(animals: List[String])
    * >>> case class Farmer(name: String, age: Long, farm: Farm)
    *
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> val client = LocalDynamoDB.client()
    *
    * >>> LocalDynamoDB.withTable(client)("farmers")('name -> S) {
    * ...   Scanamo.put(client)("farmers")(Farmer("Maggot", 75L, Farm(List("dog"))))
    * ...   Scanamo.get[Farmer](client)("farmers")(UniqueKey(KeyEquals('name, "Maggot")))
    * ... }
    * Some(Valid(Farmer(Maggot,75,Farm(List(dog)))))
    * }}}
    * or with some added syntactic sugar:
    * {{{
    * >>> import com.gu.scanamo.syntax._
    * >>> LocalDynamoDB.withTable(client)("farmers")('name -> S) {
    * ...   Scanamo.put(client)("farmers")(Farmer("Maggot", 75L, Farm(List("dog"))))
    * ...   Scanamo.get[Farmer](client)("farmers")('name -> "Maggot")
    * ... }
    * Some(Valid(Farmer(Maggot,75,Farm(List(dog)))))
    * }}}
    * Can also be used with tables that have both a hash and a range key:
    * {{{
    * >>> case class Engine(name: String, number: Int)
    * >>> LocalDynamoDB.withTable(client)("engines")('name -> S, 'number -> N) {
    * ...   Scanamo.put(client)("engines")(Engine("Thomas", 1))
    * ...   Scanamo.get[Engine](client)("engines")('name -> "Thomas" and 'number -> 1)
    * ... }
    * Some(Valid(Engine(Thomas,1)))
    * }}}
    */
  def get[T: DynamoFormat](client: AmazonDynamoDB)(tableName: String)(key: UniqueKey[_])
    : Option[ValidatedNel[DynamoReadError, T]] =
    exec(client)(ScanamoFree.get[T](tableName)(key))

  /**
    * Returns all the items in the table with matching keys
    *
    * Results are returned in the same order as the keys are provided
    *
    * {{{
    * >>> case class Farm(animals: List[String])
    * >>> case class Farmer(name: String, age: Long, farm: Farm)
    *
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> val client = LocalDynamoDB.client()
    *
    * >>> LocalDynamoDB.withTable(client)("farmers")('name -> S) {
    * ...   Scanamo.putAll(client)("farmers")(List(
    * ...     Farmer("Boggis", 43L, Farm(List("chicken"))), Farmer("Bunce", 52L, Farm(List("goose"))), Farmer("Bean", 55L, Farm(List("turkey")))
    * ...   ))
    * ...   Scanamo.getAll[Farmer](client)("farmers")(UniqueKeys(KeyList('name, List("Boggis", "Bean"))))
    * ... }
    * List(Valid(Farmer(Boggis,43,Farm(List(chicken)))), Valid(Farmer(Bean,55,Farm(List(turkey)))))
    * }}}
    * or with some added syntactic sugar:
    * {{{
    * >>> import com.gu.scanamo.syntax._
    * >>> LocalDynamoDB.withTable(client)("farmers")('name -> S) {
    * ...   Scanamo.putAll(client)("farmers")(List(
    * ...     Farmer("Boggis", 43L, Farm(List("chicken"))), Farmer("Bunce", 52L, Farm(List("goose"))), Farmer("Bean", 55L, Farm(List("turkey")))
    * ...   ))
    * ...   Scanamo.getAll[Farmer](client)("farmers")('name -> List("Boggis", "Bean"))
    * ... }
    * List(Valid(Farmer(Boggis,43,Farm(List(chicken)))), Valid(Farmer(Bean,55,Farm(List(turkey)))))
    * }}}
    * You can also retrieve items from a table with both a hash and range key
    * {{{
    * >>> case class Doctor(actor: String, regeneration: Int)
    * >>> LocalDynamoDB.withTable(client)("doctors")('actor -> S, 'regeneration -> N) {
    * ...   Scanamo.putAll(client)("doctors")(
    * ...     List(Doctor("McCoy", 9), Doctor("Ecclestone", 10), Doctor("Ecclestone", 11)))
    * ...   Scanamo.getAll[Doctor](client)("doctors")(('actor and 'regeneration) -> List("McCoy" -> 9, "Ecclestone" -> 11))
    * ... }
    * List(Valid(Doctor(McCoy,9)), Valid(Doctor(Ecclestone,11)))
    * }}}
    */
  def getAll[T: DynamoFormat](client: AmazonDynamoDB)(tableName: String)(keys: UniqueKeys[_])
    : List[ValidatedNel[DynamoReadError, T]] =
    exec(client)(ScanamoFree.getAll(tableName)(keys))


  /**
    * Deletes a single item from a table by a unique key
    *
    * {{{
    * >>> case class Farm(animals: List[String])
    * >>> case class Farmer(name: String, age: Long, farm: Farm)
    *
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> import com.gu.scanamo.syntax._
    * >>> val client = LocalDynamoDB.client()
    *
    * >>> LocalDynamoDB.withTable(client)("farmers")('name -> S) {
    * ...   Scanamo.put(client)("farmers")(Farmer("McGregor", 62L, Farm(List("rabbit"))))
    * ...   Scanamo.delete(client)("farmers")('name -> "McGregor")
    * ...   Scanamo.get[Farmer](client)("farmers")('name -> "McGregor")
    * ... }
    * None
    * }}}
    */
  def delete(client: AmazonDynamoDB)(tableName: String)(key: UniqueKey[_]): DeleteItemResult =
    exec(client)(ScanamoFree.delete(tableName)(key))

  /**
    * Lazily scans a table
    *
    * Does not cache results by default
    * {{{
    * >>> case class Bear(name: String, favouriteFood: String)
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    *
    * >>> LocalDynamoDB.withTable(client)("bears")('name -> S) {
    * ...   Scanamo.put(client)("bears")(Bear("Pooh", "honey"))
    * ...   Scanamo.put(client)("bears")(Bear("Yogi", "picnic baskets"))
    * ...   Scanamo.scan[Bear](client)("bears").toList
    * ... }
    * List(Valid(Bear(Pooh,honey)), Valid(Bear(Yogi,picnic baskets)))
    * }}}
    * Pagination is handled internally with `Streaming` result retrieving pages as necessary
    * {{{
    * >>> case class Lemming(name: String, stuff: String)
    *
    * >>> LocalDynamoDB.withTable(client)("lemmings")('name -> S) {
    * ...   Scanamo.putAll(client)("lemmings")(
    * ...     (for { _ <- 0 until 100 } yield Lemming(util.Random.nextString(500), util.Random.nextString(5000))).toList
    * ...   )
    * ...   Scanamo.scan[Lemming](client)("lemmings").toList.size
    * ... }
    * 100
    * }}}
    */
  def scan[T: DynamoFormat](client: AmazonDynamoDB)(tableName: String)
    : Streaming[ValidatedNel[DynamoReadError, T]] =
    exec(client)(ScanamoFree.scan(tableName))

  /**
    * Perform a query against a table
    *
    * This can be as simple as looking up by a hash key where a range key also exists
    * {{{
    * >>> case class Animal(species: String, number: Int)
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> val tableResult = LocalDynamoDB.createTable(client)("animals")('species -> S, 'number -> N)
    *
    * >>> val r1 = Scanamo.put(client)("animals")(Animal("Wolf", 1))
    * >>> val r2 = for { i <- 1 to 3 } Scanamo.put(client)("animals")(Animal("Pig", i))
    * >>> Scanamo.query[Animal](client)("animals")(Query(KeyEquals('species, "Pig"))).toList
    * List(Valid(Animal(Pig,1)), Valid(Animal(Pig,2)), Valid(Animal(Pig,3)))
    * }}}
    * or with some syntactic sugar
    * {{{
    * >>> import com.gu.scanamo.syntax._
    * >>> Scanamo.query[Animal](client)("animals")('species -> "Pig").toList
    * List(Valid(Animal(Pig,1)), Valid(Animal(Pig,2)), Valid(Animal(Pig,3)))
    * }}}
    * It also supports various conditions on the range key
    * {{{
    * >>> Scanamo.query[Animal](client)("animals")('species -> "Pig" and 'number < 3).toList
    * List(Valid(Animal(Pig,1)), Valid(Animal(Pig,2)))
    *
    * >>> Scanamo.query[Animal](client)("animals")('species -> "Pig" and 'number > 1).toList
    * List(Valid(Animal(Pig,2)), Valid(Animal(Pig,3)))
    *
    * >>> Scanamo.query[Animal](client)("animals")('species -> "Pig" and 'number <= 2).toList
    * List(Valid(Animal(Pig,1)), Valid(Animal(Pig,2)))
    *
    * >>> Scanamo.query[Animal](client)("animals")('species -> "Pig" and 'number >= 2).toList
    * List(Valid(Animal(Pig,2)), Valid(Animal(Pig,3)))
    *
    * >>> case class Transport(mode: String, line: String)
    * >>> LocalDynamoDB.withTable(client)("transport")('mode -> S, 'line -> S) {
    * ...   Scanamo.putAll(client)("transport")(List(
    * ...     Transport("Underground", "Circle"),
    * ...     Transport("Underground", "Metropolitan"),
    * ...     Transport("Underground", "Central")))
    * ...   Scanamo.query[Transport](client)("transport")('mode -> "Underground" and ('line beginsWith "C")).toList
    * ... }
    * List(Valid(Transport(Underground,Central)), Valid(Transport(Underground,Circle)))
    * }}}
    */
  def query[T: DynamoFormat](client: AmazonDynamoDB)(tableName: String)(query: Query[_])
    : Streaming[ValidatedNel[DynamoReadError, T]] =
    exec(client)(ScanamoFree.query(tableName)(query))
}
