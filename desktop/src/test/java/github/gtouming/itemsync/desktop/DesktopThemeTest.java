package github.gtouming.itemsync.desktop;

import javafx.css.CssParser;
import javafx.css.Stylesheet;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopThemeTest {

    @Test
    void stylesheetIsPackagedWithTheDesktopModule() {
        assertNotNull(DesktopTheme.STYLESHEET);
        assertTrue(DesktopTheme.STYLESHEET.endsWith("itemsync.css"));
    }

    @Test
    void stylesheetParses() throws Exception {
        URL url = DesktopTheme.class.getResource("/itemsync.css");
        assertNotNull(url);
        Stylesheet sheet = new CssParser().parse(url);
        assertNotNull(sheet);
        assertTrue(sheet.getRules().size() > 10);
    }
}
