package modum.io.monitor;

import java.util.Properties;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

public class MailService {
  private static Logger LOG = LoggerFactory.getLogger(MailService.class);

  private final JavaMailSenderImpl javaMailSender;

  public MailService(String host, String port, String user, String password) {
    javaMailSender = new JavaMailSenderImpl();
    javaMailSender.setUsername(user);
    javaMailSender.setPassword(password);
    Properties properties = new Properties();
    properties.setProperty("mail.transport.protocol", "smtp");
    properties.setProperty("mail.smtp.auth", "true");
    properties.setProperty("mail.smtp.starttls.enable", "true");
    properties.setProperty("mail.debug", "false");
    properties.setProperty("mail.smtp.host", host);
    properties.setProperty("mail.smtp.port", port);
    properties.setProperty("mail.smtp.ssl.trust", "*");
    javaMailSender.setJavaMailProperties(properties);
  }

  public void sendConfirmationMail(String email, String amount) {
    LOG.info("Sending confirmation mail to {}: payment of {}", email, amount);
    try {
      MimeMessageHelper messageHelper = new MimeMessageHelper(javaMailSender.createMimeMessage(), true, "UTF-8");
      messageHelper.setSubject("Payment received");
      messageHelper.setFrom("token@modum.io");
      messageHelper.setTo(email);
      messageHelper.setText("We received a payment of " + amount);
      javaMailSender.send(messageHelper.getMimeMessage());
    } catch (MessagingException e) {
      e.printStackTrace();
    }
  }

}
