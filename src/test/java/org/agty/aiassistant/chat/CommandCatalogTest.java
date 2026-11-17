package org.agty.aiassistant.chat;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandCatalogTest {
    @Test void everyImplementedCommandHasValidOfflineExamplesAndResolvableLinks() {
        Set<String> names = new HashSet<>();
        for (var command : SlashCommands.ALL) {
            assertTrue(names.add(command.name()));
            assertSame(command, SlashCommands.find(command.name().substring(1)));
            assertFalse(command.purpose().isBlank()); assertFalse(command.constraints().isBlank());
            assertFalse(command.errors().isBlank()); assertFalse(command.ui().isBlank());
            assertTrue(command.examples().size() >= 2);
            assertEquals(command.examples().size(), command.exampleContexts().size());
            for (String example : command.examples()) assertEquals(command.name(), SlashCommands.parse(example).name());
            for (String link : command.related()) assertNotNull(SlashCommands.find(link), link);
            for (String alias : command.aliases()) assertSame(command, SlashCommands.find(alias));
        }
    }
    @Test void literalSlashIsNotACommandAndUnknownInputStaysIdentifiable() {
        assertNull(SlashCommands.parse("//hello"));
        assertEquals("/missing", SlashCommands.parse("/missing arg").name());
        assertEquals("/interrupt", SlashCommands.parse("/interrupt").name());
    }
}
