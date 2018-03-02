package ecdar.mutation;

import ecdar.abstractions.Component;
import ecdar.abstractions.Edge;
import ecdar.abstractions.EdgeStatus;
import ecdar.abstractions.Location;
import ecdar.mutation.models.ComponentSimulation;
import ecdar.utility.ExpressionHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Simulation of a component.
 * It simulates the current location and clock valuations.
 * It does not simulate local variables.
 */
public class SimpleComponentSimulation implements ComponentSimulation {
    private final Component component;

    private Location currentLocation;
    private final Map<String, Double> clockValuations = new HashMap<>();
    private final Map<String, Integer> localValuations = new HashMap<>();
    private final List<String> clocks = new ArrayList<>();

    public SimpleComponentSimulation(final Component component) {
        this.component = component;
        currentLocation = component.getInitialLocation();

        component.getClocks().forEach(clock -> {
            clocks.add(clock);
            resetClock(clock);
        });
    }


    /* Getters and setters */

    @Override
    public String getName() {
        return getComponent().getName();
    }

    @Override
    public String getCurrentLocId() {
        return getCurrentLocation().getId();
    }

    @Override
    public Map<String, Integer> getLocalVariableValuations() {
        return localValuations;
    }

    @Override
    public Map<String, Double> getClockValuations() {
        return clockValuations;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    public Component getComponent() {
        return component;
    }

    /**
     * Gets clock and local variable valuations.
     * @return the valuations
     */
    public Map<String, Number> getAllValuations() {
        final Map<String, Number> valuations = new HashMap<>();

        valuations.putAll(getClockValuations());
        valuations.putAll(getLocalVariableValuations());

        return valuations;
    }


    /* Other methods */

    /**
     * Delays.
     * The delay is run successfully if the invariant of the current location still holds.
     * @param time the amount to delay in engine time units
     * @return true iff the delay was run successfully
     */
    public boolean delay(final double time) {
        clocks.forEach(c -> clockValuations.put(c, clockValuations.get(c) + time));

        final String invariant = getCurrentLocation().getInvariant();

        return invariant.isEmpty() || ExpressionHelper.evaluateBooleanExpression(invariant, getAllValuations());
    }

    /**
     * Runs an update property by updating valuations.
     * @param property the update property
     */
    private void runUpdateProperty(final String property) {
        ExpressionHelper.parseUpdate(property, getLocalVariableValuations()).forEach((key, value) -> {
            if (clocks.contains(key)) resetClock(key);
            else getLocalVariableValuations().put(key, value);
        });
    }

    /**
     * Resets a clock.
     * @param clock the clock to reset
     */
    private void resetClock(final String clock) {
        getClockValuations().put(clock, 0d);
    }

    /**
     * Gets a stream containing the available edges matching a specified action.
     * @param sync the specified synchronization output without ? or !
     * @param status the status of the action that you look for
     * @return the stream
     */
    private Stream<Edge> getAvailableEdgeStream(final String sync, final EdgeStatus status) {
        return component.getOutgoingEdges(currentLocation).stream()
                .filter(e -> e.getStatus() == status)
                .filter(e -> e.getSync().equals(sync))
                .filter(e -> e.getGuard().trim().isEmpty() ||
                        ExpressionHelper.evaluateBooleanExpression(e.getGuard(), getAllValuations()))
                .filter(e -> e.getTargetLocation().getInvariant().isEmpty() ||
                ExpressionHelper.evaluateBooleanExpression(e.getTargetLocation().getInvariant(), getAllValuations()));
    }

    /**
     * Simulates an input action.
     * @param sync synchronization property without ?
     * @throws MutationTestingException if simulation yields a non-deterministic choice, no choices, or the Universal
     * or Inconsistent locations.
     */
    public void runInputAction(final String sync) throws MutationTestingException {
        final List<Edge> edges = getAvailableEdgeStream(sync, EdgeStatus.INPUT).collect(Collectors.toList());

        if (edges.size() > 1) throw new MutationTestingException("Simulation of input " + sync +
                " yields a non-deterministic choice between " + edges.size() + " edges");

        if (edges.size() < 1) throw new MutationTestingException("Simulation of input " + sync +
                " yields a no choices. Thus, the component is not input-enabled");

        final Location newLoc = edges.get(0).getTargetLocation();

        if (newLoc.isUniversalOrInconsistent()) throw new MutationTestingException("Simulation of input " + sync +
                " yields the Universal or Inconsistent location. This should not happen");

        currentLocation = newLoc;

        runUpdateProperty(edges.get(0).getUpdate());
    }

    /**
     * Simulations an output action.
     * @param sync synchronization property without !
     * @return true iff the simulation succeeded, e.g. we found and run the transition
     * @throws MutationTestingException if simulation yields a non-deterministic choice or the Universal or
     * Inconsistent locations.
     */
    public boolean runOutputAction(final String sync) throws MutationTestingException {
        final List<Edge> edges = getAvailableEdgeStream(sync, EdgeStatus.OUTPUT).collect(Collectors.toList());

        if (edges.size() > 1) throw new MutationTestingException("Simulation of output " + sync +
                " yields a non-deterministic choice between " + edges.size() + " edges");

        if (edges.size() < 1) return false;

        final Location newLoc = edges.get(0).getTargetLocation();

        if (newLoc.isUniversalOrInconsistent()) throw new MutationTestingException("Simulation of output " + sync +
                " yields the Universal or Inconsistent location. This should not happen");

        currentLocation = newLoc;

        runUpdateProperty(edges.get(0).getUpdate());
        return true;
    }
}
