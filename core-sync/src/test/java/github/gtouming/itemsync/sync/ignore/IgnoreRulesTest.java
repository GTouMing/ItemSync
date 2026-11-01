package github.gtouming.itemsync.sync.ignore;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IgnoreRulesTest {

    @Test
    void emptyRulesIgnoreNothing() {
        IgnoreRules rules = IgnoreRules.empty();

        assertTrue(rules.isEmpty());
        assertFalse(rules.isIgnored(path("a/b.txt"), false));
    }

    @Test
    void ignoresBlankLinesAndComments() {
        IgnoreRules rules = rules("", "   ", "# comment", "*.log");

        assertTrue(rules.isIgnored(path("a.log"), false));
        assertFalse(rules.isIgnored(path("# comment"), false));
    }

    @Test
    void matchesSimpleNameAtAnyLevel() {
        IgnoreRules rules = rules("*.log");

        assertTrue(rules.isIgnored(path("a.log"), false));
        assertTrue(rules.isIgnored(path("a/b/c.log"), false));
        assertFalse(rules.isIgnored(path("a/b/c.txt"), false));
    }

    @Test
    void trailingSlashMatchesDirectoriesAndContentsOnly() {
        IgnoreRules rules = rules("build/");

        assertTrue(rules.isIgnored(path("build"), true));
        assertFalse(rules.isIgnored(path("build"), false));
        assertTrue(rules.isIgnored(path("build/x.txt"), false));
        assertTrue(rules.isIgnored(path("a/build/y.txt"), false));
    }

    @Test
    void nameWithoutSlashMatchesFilesAndDirectories() {
        IgnoreRules rules = rules("build");

        assertTrue(rules.isIgnored(path("build"), false));
        assertTrue(rules.isIgnored(path("build"), true));
        assertTrue(rules.isIgnored(path("build/x.txt"), false));
    }

    @Test
    void leadingSlashAnchorsToRoot() {
        IgnoreRules rules = rules("/logs");

        assertTrue(rules.isIgnored(path("logs"), false));
        assertTrue(rules.isIgnored(path("logs/x.txt"), false));
        assertFalse(rules.isIgnored(path("a/logs"), false));
    }

    @Test
    void slashInMiddleAnchorsToRoot() {
        IgnoreRules rules = rules("docs/tmp");

        assertTrue(rules.isIgnored(path("docs/tmp"), true));
        assertTrue(rules.isIgnored(path("docs/tmp/file.txt"), false));
        assertFalse(rules.isIgnored(path("x/docs/tmp"), true));
    }

    @Test
    void doubleStarPrefixMatchesAtAnyDepth() {
        IgnoreRules rules = rules("**/cache");

        assertTrue(rules.isIgnored(path("cache"), true));
        assertTrue(rules.isIgnored(path("a/b/cache"), true));
        assertTrue(rules.isIgnored(path("a/b/cache/x.txt"), false));
        assertFalse(rules.isIgnored(path("a/caches"), true));
    }

    @Test
    void doubleStarMiddleMatchesZeroOrMoreDirectories() {
        IgnoreRules rules = rules("a/**/b.txt");

        assertTrue(rules.isIgnored(path("a/b.txt"), false));
        assertTrue(rules.isIgnored(path("a/x/y/b.txt"), false));
        assertFalse(rules.isIgnored(path("x/a/b.txt"), false));
    }

    @Test
    void doubleStarSuffixMatchesContentsButNotDirectoryItself() {
        IgnoreRules rules = rules("a/**");

        assertFalse(rules.isIgnored(path("a"), true));
        assertTrue(rules.isIgnored(path("a/x"), false));
        assertTrue(rules.isIgnored(path("a/x/y"), false));
    }

    @Test
    void negationReincludesMatchingPaths() {
        IgnoreRules rules = rules("*.log", "!keep.log");

        assertFalse(rules.isIgnored(path("keep.log"), false));
        assertFalse(rules.isIgnored(path("a/keep.log"), false));
        assertTrue(rules.isIgnored(path("other.log"), false));
    }

    @Test
    void lastMatchingRuleWins() {
        IgnoreRules rules = rules("!keep.log", "*.log");

        assertTrue(rules.isIgnored(path("keep.log"), false));
    }

    @Test
    void escapedBangIsLiteral() {
        IgnoreRules rules = rules("\\!important");

        assertTrue(rules.isIgnored(path("!important"), false));
        assertFalse(rules.isIgnored(path("important"), false));
    }

    @Test
    void escapedHashIsLiteral() {
        IgnoreRules rules = rules("\\#tag");

        assertTrue(rules.isIgnored(path("#tag"), false));
    }

    @Test
    void questionMarkMatchesSingleCharacter() {
        IgnoreRules rules = rules("file?.txt");

        assertTrue(rules.isIgnored(path("file1.txt"), false));
        assertFalse(rules.isIgnored(path("file12.txt"), false));
    }

    @Test
    void characterClassMatchesRanges() {
        IgnoreRules rules = rules("file[0-9].txt");

        assertTrue(rules.isIgnored(path("file1.txt"), false));
        assertFalse(rules.isIgnored(path("fileA.txt"), false));
    }

    @Test
    void ignoreFileItselfIsNeverIgnored() {
        IgnoreRules rules = rules("*");

        assertFalse(rules.isIgnored(path(IgnoreRules.FILE_NAME), false));
        assertTrue(rules.isIgnored(path("anything.txt"), false));
    }

    @Test
    void trimsUnescapedTrailingSpaces() {
        IgnoreRules rules = rules("foo   ");

        assertTrue(rules.isIgnored(path("foo"), false));
    }

    @Test
    void supportsUnicodePatterns() {
        IgnoreRules rules = rules("照片/");

        assertTrue(rules.isIgnored(path("照片"), true));
        assertTrue(rules.isIgnored(path("照片/a.jpg"), false));
        assertFalse(rules.isIgnored(path("视频/a.jpg"), false));
    }

    @Test
    void rejectsNullLines() {
        assertThrows(NullPointerException.class, () -> IgnoreRules.parse(null));
        assertThrows(NullPointerException.class,
                () -> IgnoreRules.parse(java.util.Collections.singletonList(null)));
    }

    private static IgnoreRules rules(String... lines) {
        return IgnoreRules.parse(List.of(lines));
    }

    private static LogicalPath path(String value) {
        return LogicalPath.of(value);
    }
}
