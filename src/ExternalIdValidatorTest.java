//test cases
import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalIdValidatorTest {

    @Test
    public void testNullComparison() {
        assertFalse(
                ExternalIdValidator.compareExternalIds(null, "INS123")
        );
    }

    @Test
    public void testEqualIds() {
        assertTrue(
                ExternalIdValidator.compareExternalIds("INS123", "INS123")
        );
    }

    @Test
    public void testDifferentIds() {
        assertFalse(
                ExternalIdValidator.compareExternalIds("INS123", "INS999")
        );
    }
}
