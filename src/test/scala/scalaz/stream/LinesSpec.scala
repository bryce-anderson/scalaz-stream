package scalaz.stream

import java.nio.BufferOverflowException

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.stream.text.{LengthExceeded, lines}

object LinesSpec extends Properties("text") {

  val samples = 0 until 5 flatMap { i => List("\r\n", "\n").map { s =>
    "Hello&World.&Foo&Bar&".replace("&", s*i)
    }
  }

  // behavior should be identical to that of scala.io.Source
  def checkLine(s: String): Boolean = {
    val source = scala.io.Source.fromString(s).getLines().toList
    emitAll(s.toCharArray.map(_.toString)).pipe(lines()).toList == source &&
      emit(s).pipe(lines()).toList == source
  }

  property("lines()") = secure {
    samples.forall(checkLine)
  }

  property("lines(n) should fail for lines with length greater than n") = secure {
    val error = classOf[LengthExceeded]

    emit("foo\nbar").pipe(lines(3)).toList == List("foo", "bar")    &&   // OK input
    Process("foo\n", "bar").pipe(lines(3)).toList == List("foo", "bar") &&   // OK input
    Process("foo", "\nbar").pipe(lines(3)).toList == List("foo", "bar") &&   // OK input
    throws(error){ emit("foo").pipe(lines(2)).run[Task].run }       &&
    throws(error){ emit("foo\nbarr").pipe(lines(3)).run[Task].run } &&
    throws(error){ emit("fooo\nbar").pipe(lines(3)).run[Task].run }
  }

  property("lines(n) can recover from lines longer than n") = {
    import Gen._

    val stringWithNewlinesGen: Gen[String] =
      listOf(frequency((5, alphaChar), (1, oneOf('\n', '\r')))).map(_.mkString)

    def rmWhitespace(s: String): String = s.replaceAll("\\s", "")

    forAll(listOf(stringWithNewlinesGen)) { xs: List[String] =>
      val stripped = rmWhitespace(xs.mkString)
      val maxLength = Gen.choose(1, stripped.length).sample.getOrElse(1)
      val nonFailingLines = lines(maxLength).onFailure {
        case LengthExceeded(_, s) => emitAll(s.grouped(maxLength).toList)
      }.repeat

      val allLines = emitAll(xs).pipe(nonFailingLines).toList
      allLines.forall(_.length <= maxLength) &&
        rmWhitespace(allLines.mkString) == stripped
    }
  }

  property("splitString(s) should yield the same results as s.split(\"\\r\\n|\\n\", -1)") = {
    import java.util.regex.Pattern
    import Gen._

    val p = Pattern.compile("\r\n|\n")

    val stringWithNewlinesGen: Gen[String] =
      listOf(frequency((5, alphaChar), (1, '\n'), (1, '\r'), (1, "\r\n"))).map(_.mkString)

    forAll(stringWithNewlinesGen) { s =>
      text.splitLines(s) == p.split(s, -1).toVector
    }
  }
}
