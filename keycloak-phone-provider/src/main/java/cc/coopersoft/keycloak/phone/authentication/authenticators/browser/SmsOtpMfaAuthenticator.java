package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import cc.coopersoft.common.OptionalUtils;
import cc.coopersoft.keycloak.phone.Utils;
import cc.coopersoft.keycloak.phone.authentication.forms.SupportPhonePages;
import cc.coopersoft.keycloak.phone.authentication.requiredactions.ConfigSmsOtpRequiredAction;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialModel;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProvider;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProviderFactory;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneProvider;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.CredentialValidator;
import org.keycloak.credential.CredentialProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.validation.Validation;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Optional;

import static cc.coopersoft.keycloak.phone.authentication.authenticators.browser.PhoneUsernamePasswordForm.VERIFIED_PHONE_NUMBER;
import static cc.coopersoft.keycloak.phone.authentication.forms.SupportPhonePages.ATTRIBUTE_SUPPORT_PHONE;

public class SmsOtpMfaAuthenticator implements Authenticator, CredentialValidator<PhoneOtpCredentialProvider> {

  private static final Logger logger = Logger.getLogger(SmsOtpMfaAuthenticator.class);

  private static final String PAGE = "login-sms-otp.ftl";

  protected boolean validateCookie(AuthenticationFlowContext context) {
    if (Utils.getOtpExpires(context.getSession()) <= 0)
      return false;

    var invalid = PhoneOtpCredentialModel.getSmsOtpCredentialData(context.getUser())
        .map(PhoneOtpCredentialModel.SmsOtpCredentialData::isSecretInvalid)
        .orElse(true);

    if (invalid)
      return false;

    return Optional.of(context.getHttpRequest().getHttpHeaders().getCookies())
            .flatMap(cookies ->
                    Optional.ofNullable(cookies.get("SMS_OTP_ANSWERED"))
                    .flatMap(cookie -> OptionalUtils.ofBlank(cookie.getValue()))
                    .flatMap(credentialId ->
                        Optional.ofNullable(cookies.get(credentialId))
                            .flatMap(cookie -> OptionalUtils.ofBlank(cookie.getValue()))
                            .map(secret ->  context.getUser()
                                .credentialManager()
                                .isValid(new UserCredentialModel(credentialId, getType(context.getSession()), secret)))
                    )
            ).orElse(false);
  }

  protected void setCookie(AuthenticationFlowContext context, String credentialId, String secret) {


    int maxCookieAge = Utils.getOtpExpires(context.getSession());

    if (maxCookieAge <= 0 ){
      return;
    }

    URI uri = context.getUriInfo()
        .getBaseUriBuilder()
        .path("realms")
        .path(context.getRealm().getName())
        .build();

    addCookie(context, "SMS_OTP_ANSWERED", credentialId,
        uri.getRawPath(),
        null, null,
        maxCookieAge,
        false, true);
    addCookie(context, credentialId, secret,
        uri.getRawPath(),
        null, null,
        maxCookieAge,
        false, true);
  }

  public void addCookie(AuthenticationFlowContext context, String name, String value, String path, String domain, String comment, int maxAge, boolean secure, boolean httpOnly) {
    // 获取 KeycloakSession
    KeycloakSession session = context.getSession();

    // 获取 HttpServletResponse 对象（通过 KeycloakSession 获取）
    HttpServletResponse response = (HttpServletResponse) session.getContext().getHttpResponse();

    // 创建 Cookie 对象
    Cookie cookie = new Cookie(name, value);
    cookie.setPath(path);
    cookie.setDomain(domain);
    cookie.setMaxAge(maxAge);
    cookie.setSecure(secure);
    cookie.setHttpOnly(httpOnly);

    // 如果 comment 不为空，则在设置 cookie 的过程中手动添加 comment
    if (comment != null && !comment.isEmpty()) {
      cookie.setComment(comment);
    }

    // 将 Cookie 添加到响应
    response.addCookie(cookie);

    // 可选：手动设置 Set-Cookie 头来包含 comment
    StringBuilder cookieHeader = new StringBuilder();
    cookieHeader.append(cookie.getName()).append("=").append(cookie.getValue())
            .append("; Path=").append(cookie.getPath())
            .append("; Max-Age=").append(cookie.getMaxAge())
            .append("; HttpOnly=").append(cookie.isHttpOnly())
            .append("; Secure=").append(cookie.getSecure());

    if (comment != null && !comment.isEmpty()) {
      cookieHeader.append("; Comment=").append(comment);
    }

    // 添加到响应头
    response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader.toString());
  }

  @Override
  public PhoneOtpCredentialProvider getCredentialProvider(KeycloakSession session) {
    return (PhoneOtpCredentialProvider) session.getProvider(CredentialProvider.class, PhoneOtpCredentialProviderFactory.PROVIDER_ID);
  }

  private String getCredentialPhoneNumber(UserModel user){
    return PhoneOtpCredentialModel.getSmsOtpCredentialData(user)
        .map(PhoneOtpCredentialModel.SmsOtpCredentialData::getPhoneNumber)
        .orElseThrow(() -> new IllegalStateException("Not have OTP Credential"));
  }

  @Override
  public void authenticate(AuthenticationFlowContext context) {

    if (validateCookie(context)) {
      context.success();
      return;
    }

    String phoneNumber = getCredentialPhoneNumber(context.getUser());

    boolean verified = OptionalUtils.ofBlank(context.getAuthenticationSession().getAuthNote(VERIFIED_PHONE_NUMBER))
        .map(number -> number.equalsIgnoreCase(phoneNumber))
        .orElse(false);
    if (verified) {
      context.success();
      return;
    }

    PhoneProvider phoneProvider = context.getSession().getProvider(PhoneProvider.class);
    try {
      int expires = phoneProvider.sendTokenCode(phoneNumber,context.getConnection().getRemoteAddr(),
          TokenCodeType.OTP, null);
      context.form()
          .setInfo("codeSent", phoneNumber)
          .setAttribute("expires", expires)
          .setAttribute("initSend",true);
    } catch (ForbiddenException e) {
      logger.warn("otp send code Forbidden Exception!", e);
      context.form().setError(SupportPhonePages.Errors.ABUSED.message());
    } catch (Exception e) {
      logger.warn("otp send code Exception!", e);
      context.form().setError(SupportPhonePages.Errors.FAIL.message());
    }

    var credentialData = new PhoneOtpCredentialModel.SmsOtpCredentialData(phoneNumber,0);
    PhoneOtpCredentialModel.updateOtpCredential(context.getUser(),credentialData,null);

    Response challenge = challenge(context,phoneNumber);
    context.challenge(challenge);
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
    String secret = formData.getFirst("code");
    String credentialId = formData.getFirst("credentialId");

    String phoneNumber = getCredentialPhoneNumber(context.getUser());

    if (credentialId == null || credentialId.isEmpty()) {
      var defaultOtpCredential = getCredentialProvider(context.getSession())
          .getDefaultCredential(context.getSession(), context.getRealm(), context.getUser());
      credentialId = defaultOtpCredential==null ? "" : defaultOtpCredential.getId();
    }

    if (Validation.isBlank(secret)){
      context.form()
          .setError(SupportPhonePages.Errors.NOT_MATCH.message());
      Response challenge = challenge(context,phoneNumber);
      context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
    }

    UserCredentialModel input = new UserCredentialModel(credentialId, getType(context.getSession()), secret);

    boolean validated = getCredentialProvider(context.getSession()).isValid(context.getRealm(), context.getUser(), input);

    if (!validated) {
      context.form()
          .setError(SupportPhonePages.Errors.NOT_MATCH.message());
      Response challenge = challenge(context,phoneNumber);
      context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
      return;
    }
    setCookie(context,credentialId,secret);
    context.success();
  }

  protected Response challenge(AuthenticationFlowContext context,String phoneNumber) {
    return context.form()
        .setAttribute(ATTRIBUTE_SUPPORT_PHONE, true)
        .setAttribute(SupportPhonePages.ATTEMPTED_PHONE_NUMBER,phoneNumber)
        .createForm(PAGE);
  }

  @Override
  public boolean requiresUser() {
    return true;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return getCredentialProvider(session).isConfiguredFor(realm, user, getType(session));
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    user.addRequiredAction(ConfigSmsOtpRequiredAction.PROVIDER_ID);
  }

  @Override
  public void close() {

  }
}
