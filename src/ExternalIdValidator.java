//test cases
import java.util.Objects;

public class ExternalIdValidator {

    public static boolean compareExternalIds(String userId, String instructorId) {
        return Objects.equals(userId, instructorId);
    }

}
