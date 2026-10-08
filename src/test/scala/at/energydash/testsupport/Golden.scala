package at.energydash.testsupport

import org.scalatest.Assertions

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * Golden files under `src/test/resources/protocol/golden/` (protocol-changes.md §3). A test passes when the
 * file equals one of the candidates (several when a run crosses midnight). With `-Deda.golden.update=true`
 * (`scripts/dev/test.sh --update-golden`, refused in CI) the file is rewritten instead; review with `git diff`.
 */
object Golden extends Assertions {
  val Root = new File("src/test/resources/protocol/golden")
  def updating: Boolean = sys.props.get("eda.golden.update").contains("true")

  def check(relPath: String, candidates: Seq[String]): Unit = {
    require(candidates.nonEmpty)
    val file = new File(Root, relPath)
    if (updating) {
      file.getParentFile.mkdirs()
      Files.write(file.toPath, candidates.head.getBytes(UTF_8))
    } else {
      if (!file.isFile) fail(s"golden file $file missing — create it with scripts/dev/test.sh --update-golden and review it")
      val expected = new String(Files.readAllBytes(file.toPath), UTF_8)
      if (!candidates.contains(expected)) fail(s"$file differs from the output:\n${firstDiff(expected, candidates.head)}")
    }
  }

  /** Every golden file, as relative paths (guard: no orphans). */
  def all: Set[String] = {
    def walk(f: File): Seq[File] = if (f.isDirectory) f.listFiles().toSeq.flatMap(walk) else Seq(f)
    if (!Root.isDirectory) Set.empty else walk(Root).map(f => Root.toPath.relativize(f.toPath).toString).toSet
  }

  private def firstDiff(expected: String, actual: String): String = {
    val (e, a) = (expected.linesIterator.toVector, actual.linesIterator.toVector)
    val i = e.indices.find(i => i >= a.length || e(i) != a(i)).getOrElse(e.length)
    s"line ${i + 1}:\n  golden: ${e.lift(i).getOrElse("<end>")}\n  actual: ${a.lift(i).getOrElse("<end>")}"
  }
}
