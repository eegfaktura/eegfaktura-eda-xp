package at.energydash.testsupport

import org.scalatest.exceptions.TestPendingException
import org.scalatest.Assertions

/**
 * Defect test for a known error (open point EDA-261008-ca2): the body asserts the **correct** behaviour.
 * While the defect exists the body fails and the test is reported as *pending*; once the body passes the
 * test **fails**, so the marker is removed together with the fix. The id names the entry in the private
 * tracking files (`docs/eda-xp/errors/<id>-*.md`). Keep time-outs inside the body short (≤ 3 s).
 */
object KnownError extends Assertions {
  def knownError(id: String)(body: => Any): Unit = {
    val fixed =
      try { body; true }
      catch { case _: Exception | _: AssertionError => false }
    if (fixed) fail(s"known error $id seems fixed — remove the knownError marker")
    else throw new TestPendingException
  }
}
