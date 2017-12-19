package SW9.controllers;
import SW9.Ecdar;
import SW9.abstractions.ComponentOperator;
import SW9.abstractions.EcdarSystemEdge;
import SW9.abstractions.SystemModel;
import SW9.presentations.DropDownMenu;
import SW9.presentations.MenuElement;
import com.jfoenix.controls.JFXPopup;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controller for ComponentOperators
 */
public class ComponentOperatorController implements Initializable {
    public Polygon frame;
    public StackPane root;
    public Label label;

    private final ObjectProperty<SystemModel> system = new SimpleObjectProperty<>();
    private ComponentOperator operator;
    private final BooleanProperty hasParent = new SimpleBooleanProperty(false);
    private Circle dropDownMenuHelperCircle;


    // Properties

    public ComponentOperator getOperator() { return operator; }

    public void setOperator(final ComponentOperator operator) {
        this.operator = operator;
    }

    public void setSystem(final SystemModel system) {
        this.system.set(system);
    }

    public SystemModel getSystem() {
        return system.get();
    }


    // Initialize

    @Override
    public void initialize(final URL location, final ResourceBundle resources) {
        system.addListener(((observable, oldValue, newValue) -> {
            initializeDropDownMenu();
        }));
    }

    private void initializeDropDownMenu() {
        dropDownMenuHelperCircle = new Circle(5);
        dropDownMenuHelperCircle.setOpacity(0);
        dropDownMenuHelperCircle.setMouseTransparent(true);
        root.getChildren().add(dropDownMenuHelperCircle);
    }

    /**
     * Shows a context menu.
     * This method creates the menu object itself
     * (rather than having it be created in an initialize method),
     * since the parent of the root is not yet defined when initializing.
     * @param mouseEvent
     */
    private void showContextMenu(MouseEvent mouseEvent) {
        dropDownMenuHelperCircle.setLayoutX(mouseEvent.getX());
        dropDownMenuHelperCircle.setLayoutY(mouseEvent.getY());

        final DropDownMenu contextMenu = new DropDownMenu((Pane) root.getParent().getParent(), dropDownMenuHelperCircle, 230, true);

        contextMenu.addMenuElement(new MenuElement("Draw Edge")
                .setClickable(() -> {
                    final EcdarSystemEdge edge = new EcdarSystemEdge(operator);
                    getSystem().addEdge(edge);

                    contextMenu.close();
                }));

        contextMenu.addSpacerElement();

        contextMenu.addMenuElement(new MenuElement("Delete")
                .setClickable(() -> {
                    // TODO
                    contextMenu.close();
                }));

        contextMenu.show(JFXPopup.PopupVPosition.TOP, JFXPopup.PopupHPosition.LEFT, 0, 0);
    }

    @FXML
    private void onMouseClicked(final MouseEvent event) {
        event.consume();

        final EcdarSystemEdge unfinishedEdge = getSystem().getUnfinishedEdge();

        // if primary clicked and there is an unfinished edge, finish it with the system root as target
        if (unfinishedEdge != null && event.getButton().equals(MouseButton.PRIMARY)) {
            // If edge source is higher than operator
            if (unfinishedEdge.getSource().getEdgeY().getValue().intValue() < operator.getEdgeY().getValue().intValue()) {
                finishParentEdge(unfinishedEdge);
            } else {
                finishChildEdge(unfinishedEdge);
            }
            return;
        }

        // If secondary clicked, show context menu
        if (event.getButton().equals(MouseButton.SECONDARY)) {
            showContextMenu(event);
        }
    }

    private void finishChildEdge(final EcdarSystemEdge unfinishedEdge) {
        unfinishedEdge.setTarget(operator);
    }

    /**
     * Finishes an unfinished edge and define the edge as the parent edge of the operator.
     * @param unfinishedEdge the edge
     */
    private void finishParentEdge(final EcdarSystemEdge unfinishedEdge) {
        // If already has edge, give error
        if (hasParent.get()) {
            Ecdar.showToast("This component operator already has a parent.");
            return;
        }

        unfinishedEdge.setTarget(operator);
        hasParent.set(true);

        // Update state, if edge changes state
        unfinishedEdge.getTargetProperty().addListener(((observable, oldValue, newValue) -> hasParent.set(operator.equals(newValue))));
    }
}
