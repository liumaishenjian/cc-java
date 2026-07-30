package io.github.liumaishenjian.ccjava.tools.local.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RipgrepJsonLinesParserTest {

    @Test
    void parsesColonPathContextAndMultilineMatchWithoutDelimiterSplitting() throws Exception {
        RipgrepJsonLinesParser parser = new RipgrepJsonLinesParser();

        RipgrepParsedResult result = parser.parse(List.of(
                """
                {"type":"begin","data":{"path":{"text":"src:generated\\\\Agent.java"}}}
                """.strip(),
                """
                {"type":"context","data":{"path":{"text":"src:generated\\\\Agent.java"},"lines":{"text":"before\\n"},"line_number":8,"absolute_offset":70,"submatches":[]}}
                """.strip(),
                """
                {"type":"match","data":{"path":{"text":"src:generated\\\\Agent.java"},"lines":{"text":"first need\\nsecond need\\n"},"line_number":9,"absolute_offset":77,"submatches":[{"match":{"text":"need"},"start":6,"end":10},{"match":{"text":"need"},"start":18,"end":22}]}}
                """.strip(),
                """
                {"type":"end","data":{"path":{"text":"src:generated\\\\Agent.java"},"binary_offset":null,"stats":{"elapsed":{"secs":0,"nanos":1,"human":"0.0s"},"searches":1,"searches_with_match":1,"bytes_searched":30,"bytes_printed":20,"matched_lines":1,"matches":2}}}
                """.strip(),
                """
                {"type":"summary","data":{"elapsed_total":{"human":"0.0s"},"stats":{"matched_lines":1,"matches":2}}}
                """.strip()));

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().getFirst().kind())
                .isEqualTo(RipgrepJsonEvent.LineKind.CONTEXT);
        RipgrepJsonEvent.SearchLine match = result.content().get(1);
        assertThat(match.path()).isEqualTo("src:generated\\Agent.java");
        assertThat(match.text()).isEqualTo("first need\nsecond need\n");
        assertThat(match.submatches()).containsExactly(
                new RipgrepJsonEvent.Submatch(6, 10, "need"),
                new RipgrepJsonEvent.Submatch(18, 22, "need"));
        assertThat(result.files()).containsExactly("src:generated\\Agent.java");
        assertThat(result.counts()).containsEntry("src:generated\\Agent.java", 1L);
        assertThat(result.summary()).isEqualTo(new RipgrepJsonEvent.Summary(1, 2));
        assertThat(result.ignoredEvents()).isZero();
    }

    @Test
    void preservesRawWindowsAbsolutePathForWorkspaceGuardToRejectLater() throws Exception {
        RipgrepParsedResult result = new RipgrepJsonLinesParser().parse(List.of(
                """
                {"type":"match","data":{"path":{"text":"C:\\\\work\\\\Agent.java"},"lines":{"text":"needle\\n"},"line_number":1,"absolute_offset":0,"submatches":[{"match":{"text":"needle"},"start":0,"end":6}]}}
                """.strip()));

        assertThat(result.content().getFirst().path()).isEqualTo("C:\\work\\Agent.java");
    }

    @Test
    void ignoresUnknownAndIncompleteKnownEventsButRejectsAmbiguousJson() throws Exception {
        RipgrepJsonLinesParser parser = new RipgrepJsonLinesParser();

        RipgrepParsedResult result = parser.parse(List.of(
                """
                {"type":"future","data":{"path":{"text":"future.java"}}}
                """.strip(),
                """
                {"type":"match","data":{"path":{"text":"missing-lines.java"},"line_number":1,"absolute_offset":0,"submatches":[]}}
                """.strip(),
                ""));

        assertThat(result.content()).isEmpty();
        assertThat(result.ignoredEvents()).isEqualTo(3);
        assertThatThrownBy(() -> parser.parse(List.of(
                        """
                        {"type":"match","type":"context","data":{}}
                        """.strip())))
                .isInstanceOf(RipgrepJsonParseException.class)
                .hasMessage("ripgrep JSON 包含重复字段");
        assertThatThrownBy(() -> parser.parse(List.of("{broken")))
                .isInstanceOf(RipgrepJsonParseException.class)
                .hasMessage("ripgrep JSON 语法无效");
    }

    @Test
    void rejectsBinaryBytesEventsRatherThanLossyDecodingThem() throws Exception {
        RipgrepParsedResult result = new RipgrepJsonLinesParser().parse(List.of(
                """
                {"type":"match","data":{"path":{"bytes":"AP8="},"lines":{"bytes":"AP8="},"line_number":1,"absolute_offset":0,"submatches":[]}}
                """.strip()));

        assertThat(result.content()).isEmpty();
        assertThat(result.ignoredEvents()).isEqualTo(1);
    }

    @Test
    void enforcesLineTotalAndEventBoundsWithoutEchoingInput() {
        RipgrepJsonLinesParser lineParser = new RipgrepJsonLinesParser(8, 16, 2);
        assertThatThrownBy(() -> lineParser.parse(List.of("{\"private\":\"sentinel\"}")))
                .isInstanceOf(RipgrepJsonParseException.class)
                .hasMessage("单条 ripgrep JSON 事件超过上限")
                .hasMessageNotContaining("sentinel");

        RipgrepJsonLinesParser totalParser = new RipgrepJsonLinesParser(8, 10, 4);
        assertThatThrownBy(() -> totalParser.parse(List.of("{}", "{}", "{}", "{}")))
                .isInstanceOf(RipgrepJsonParseException.class)
                .hasMessage("ripgrep JSON 总输入超过上限");

        RipgrepJsonLinesParser eventParser = new RipgrepJsonLinesParser(8, 24, 1);
        assertThatThrownBy(() -> eventParser.parse(List.of("{}", "{}")))
                .isInstanceOf(RipgrepJsonParseException.class)
                .hasMessage("ripgrep JSON 事件数量超过上限");
    }
}
