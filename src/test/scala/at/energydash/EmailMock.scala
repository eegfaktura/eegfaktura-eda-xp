package at.energydash

import at.energydash.actors.MockedSMTPProvider

import java.util.Properties

trait EmailMock {
  private val mockedSession = javax.mail.Session.getDefaultInstance(new Properties() {
    {
      put("mail.transport.protocol.rfc822", "mocked")
    }
  })
  mockedSession.setProvider(new MockedSMTPProvider)

}
