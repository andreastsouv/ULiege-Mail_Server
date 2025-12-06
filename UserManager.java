import java.util.HashSet;
import java.util.Set;

public class UserManager {

    private final Set<String> validUsers = new HashSet<>();

    public UserManager() {
        // You can later load from file if you want
        validUsers.add("dcd@uliege.be");
        validUsers.add("vj@uliege.be");
        validUsers.add("dcd@gembloux.uliege.be");
        validUsers.add("vj@gembloux.uliege.be");
        validUsers.add("dcd@info.uliege.be");
        validUsers.add("vj@info.uliege.be");
    }

    public boolean isValidUser(String email, String domainOfThisServer) {
        // Only authenticate users of this server's domain
        if (!email.endsWith("@" + domainOfThisServer)) {
            return false;
        }
        return validUsers.contains(email);
    }

    public boolean checkPassword(String email, String password) {
        // All passwords are "password"
        return isValidUser(email, extractDomain(email)) && "password".equals(password);
    }

    private String extractDomain(String email) {
        int at = email.indexOf('@');
        if (at == -1) return "";
        return email.substring(at + 1);
    }
}
