
INSERT INTO eda.tenantconfig (tenant, domain, host, imapport, smtpport, smtphost, username, pass, imap_security, smtp_security, active)
                      VALUES ('myeeg', 'email.com', 'email.com', 143, 25, 'smtp.mail.com', 'sepp', 'password', 'STARTTLS', 'STARTTLS', true);

INSERT INTO eda.conversation (id, conversation)
                      VALUES ('RC100699202407221900383040000107598', '{"conversationId":"RC100699202407221900383040000107598","messageId":"RC100699202407221900383040000107597","sender":"RC100699","receiver":"AT003100","messageCode":"ANFORDERUNG_ECON","messageCodeVersion":"02.00","requestId":"48NaALA","meter":{"meteringPoint":"AT0031000000099000000000000005832","direction":"GENERATION","partFact":100},"ecId":"AT00310000000RC100699EGR000600001"}');
INSERT INTO eda.conversation (id, conversation)
                      VALUES ('RC102537202407222114235490000107920', '{"conversationId":"RC102537202407222114235490000107920","messageId":"RC102537202407222114235490000107919","sender":"RC102537","receiver":"AT003000","messageCode":"ANFORDERUNG_PT","messageCodeVersion":"03.00","requestId":"F8kbZt4","meter":{"meteringPoint":"AT0030000000000000000000030083164"},"ecId":"AT00300000000RC102537000000971834","timeline":{"from":1719784800000,"to":1721684700000}}');


