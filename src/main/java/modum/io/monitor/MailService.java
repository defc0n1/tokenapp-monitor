package modum.io.monitor;

import java.io.IOException;
import java.util.Properties;
import javax.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import spark.utils.IOUtils;

public class MailService {
  private static Logger LOG = LoggerFactory.getLogger(MailService.class);

  private final JavaMailSenderImpl javaMailSender;
  private final TemplateEngine templateEngine;
  private final String bccAddress;

  public MailService(String host, String port, String user, String password,
      String bccAddress) {
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
    templateEngine = new TemplateEngine();
    templateEngine.setTemplateResolver(new ClassLoaderTemplateResolver());
    this.bccAddress = bccAddress;
  }

  public void sendConfirmationMail(String email, String amount) {
    LOG.info("Sending confirmation mail to {}: payment of {}", email, amount);
    try {
      MimeMessageHelper messageHelper = new MimeMessageHelper(javaMailSender.createMimeMessage(), true, "UTF-8");
      messageHelper.setSubject("Payment received");
      messageHelper.setFrom("token@modum.io");
      messageHelper.setTo(email);
      messageHelper.setBcc(bccAddress);
      setEmailContent(messageHelper, amount);
      javaMailSender.send(messageHelper.getMimeMessage());
    } catch (MessagingException | IOException e) {
      LOG.error("Could not send email to {}. Error: {} {}", email, e.getMessage(), e.getCause());
    }
  }

  public void setEmailContent(MimeMessageHelper messageHelper, String amount)
      throws MessagingException, IOException {
    Context context = new Context();
    context.setVariable("amount", amount);
    String html5Content = templateEngine.process("templates/confirmation_email.html", context);
    messageHelper.setText(html5Content, true);

    final InputStreamSource modumLogoImage =
        new ByteArrayResource(IOUtils.toByteArray(this.getClass().getResourceAsStream(
            "/templates/modum_logo.png")));
    messageHelper.addInline("modumLogo", modumLogoImage, "image/png");
  }

}
