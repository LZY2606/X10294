package a429.event;

import a429.db.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Serialises/reads counterexample replay outcomes into event payloads. */
public final class CounterexampleMapper {
    private CounterexampleMapper() {
    }

    public static CounterexampleResult fromEvent(Event ev) {
        JsonNode p = ev.payload();
        List<CounterexampleResult.Outcome> outcomes = new ArrayList<>();
        for (JsonNode o : p.path("outcomes")) {
            outcomes.add(new CounterexampleResult.Outcome(
                    o.path("check").asText(),
                    o.path("expectedAccepted").asBoolean(),
                    o.path("observed").asBoolean(),
                    o.path("adopted").asBoolean(),
                    Json.MAPPER.convertValue(o.path("observedValue"), Object.class),
                    Json.MAPPER.convertValue(o.path("expectedValue"), Object.class),
                    o.path("rationale").asText()));
        }
        List<CounterexampleResult.UnchangedObject> unchanged = new ArrayList<>();
        for (JsonNode u : p.path("unaffectedObjects")) {
            unchanged.add(new CounterexampleResult.UnchangedObject(u.path("objectId").asText(),
                    u.path("aspect").asText(),
                    Json.MAPPER.convertValue(u.path("value"), Object.class)));
        }
        return new CounterexampleResult(p.path("mutationId").asText(),
                p.path("title").asText(), p.path("alters").asText(),
                p.path("directSourceWordId").asText(), outcomes, unchanged,
                p.path("baselineRef").asText(), ev.eventId());
    }
}
