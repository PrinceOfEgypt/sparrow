package com.sparrowwallet.sparrow.transaction;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTOutput;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.sparrow.AppController;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.ElectrumServer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.layout.Pane;
import javafx.util.StringConverter;
import org.controlsfx.control.MasterDetailPane;
import org.fxmisc.richtext.CodeArea;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.*;

public class TransactionController implements Initializable {

    @FXML
    private Node tabContent;

    @FXML
    private MasterDetailPane transactionMasterDetail;

    @FXML
    private TreeView<TransactionForm> txtree;

    @FXML
    private Pane txpane;

    @FXML
    private CodeArea txhex;

    private Transaction transaction;
    private PSBT psbt;
    private BlockTransaction blockTransaction;

    private TransactionView initialView;
    private Integer initialIndex;

    private int selectedInputIndex = -1;
    private int selectedOutputIndex = -1;

    private int highestInputIndex;
    private int highestOutputIndex;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    private void initializeView() {
        highestInputIndex = Math.min(transaction.getInputs().size(), PageForm.PAGE_SIZE);
        highestOutputIndex = Math.min(transaction.getOutputs().size(), PageForm.PAGE_SIZE);

        initializeTxTree();
        transactionMasterDetail.setShowDetailNode(AppController.showTxHexProperty);
        refreshTxHex();
        fetchThisAndInputBlockTransactions(0, highestInputIndex);
        fetchOutputBlockTransactions(0, highestOutputIndex);
    }

    private void initializeTxTree() {
        HeadersForm headersForm = (psbt != null ? new HeadersForm(psbt) : (blockTransaction != null ? new HeadersForm(blockTransaction) : new HeadersForm(transaction)));
        TreeItem<TransactionForm> rootItem = new TreeItem<>(headersForm);
        rootItem.setExpanded(true);

        InputsForm inputsForm = (psbt != null ? new InputsForm(psbt) : (blockTransaction != null ? new InputsForm(blockTransaction) : new InputsForm(transaction)));
        TreeItem<TransactionForm> inputsItem = new TreeItem<>(inputsForm);
        inputsItem.setExpanded(true);
        boolean inputPagingAdded = false;
        for(int i = 0; i < transaction.getInputs().size(); i++) {
            if(i < PageForm.PAGE_SIZE || (TransactionView.INPUT.equals(initialView) && i == initialIndex)) {
                TreeItem<TransactionForm> inputItem = createInputTreeItem(i);
                inputsItem.getChildren().add(inputItem);
            } else if(!inputPagingAdded) {
                PageForm pageForm = new PageForm(TransactionView.INPUT, i, i + PageForm.PAGE_SIZE);
                TreeItem<TransactionForm> pageItem = new TreeItem<>(pageForm);
                inputsItem.getChildren().add(pageItem);
                inputPagingAdded = true;
            }
        }

        OutputsForm outputsForm = (psbt != null ? new OutputsForm(psbt) : (blockTransaction != null ? new OutputsForm(blockTransaction) : new OutputsForm(transaction)));
        TreeItem<TransactionForm> outputsItem = new TreeItem<>(outputsForm);
        outputsItem.setExpanded(true);
        boolean outputPagingAdded = false;
        for(int i = 0; i < transaction.getOutputs().size(); i++) {
            if(i < PageForm.PAGE_SIZE || (TransactionView.OUTPUT.equals(initialView) && i == initialIndex)) {
                TreeItem<TransactionForm> outputItem = createOutputTreeItem(i);
                outputsItem.getChildren().add(outputItem);
            } else if(!outputPagingAdded) {
                PageForm pageForm = new PageForm(TransactionView.OUTPUT, i, i + PageForm.PAGE_SIZE);
                TreeItem<TransactionForm> pageItem = new TreeItem<>(pageForm);
                outputsItem.getChildren().add(pageItem);
                outputPagingAdded = true;
            }
        }

        rootItem.getChildren().add(inputsItem);
        rootItem.getChildren().add(outputsItem);
        txtree.setRoot(rootItem);

        txtree.setCellFactory(p -> new TextFieldTreeCell<>(new StringConverter<TransactionForm>() {
            @Override
            public String toString(TransactionForm transactionForm) {
                return transactionForm.toString();
            }

            @Override
            public TransactionForm fromString(String string) {
                throw new IllegalStateException("No editing");
            }
        }));

        txtree.getSelectionModel().selectedItemProperty().addListener((observable, old_val, selectedItem) -> {
            TransactionForm transactionForm = selectedItem.getValue();
            if(transactionForm instanceof PageForm) {
                PageForm pageForm = (PageForm)transactionForm;
                Optional<TreeItem<TransactionForm>> optParentItem = txtree.getRoot().getChildren().stream()
                        .filter(item -> item.getValue().getView().equals(pageForm.getView().equals(TransactionView.INPUT) ? TransactionView.INPUTS : TransactionView.OUTPUTS)).findFirst();

                if(optParentItem.isPresent()) {
                    TreeItem<TransactionForm> parentItem = optParentItem.get();
                    parentItem.getChildren().remove(selectedItem);

                    int max = pageForm.getView().equals(TransactionView.INPUT) ? transaction.getInputs().size() : transaction.getOutputs().size();
                    for(int i = pageForm.getPageStart(); i < max && i < pageForm.getPageEnd(); i++) {
                        TreeItem<TransactionForm> newItem = pageForm.getView().equals(TransactionView.INPUT) ? createInputTreeItem(i) : createOutputTreeItem(i);
                        parentItem.getChildren().add(newItem);
                    }

                    if(pageForm.getPageEnd() < max) {
                        PageForm nextPageForm = new PageForm(pageForm.getView(), pageForm.getPageStart() + PageForm.PAGE_SIZE, pageForm.getPageEnd() + PageForm.PAGE_SIZE);
                        TreeItem<TransactionForm> nextPageItem = new TreeItem<>(nextPageForm);
                        parentItem.getChildren().add(nextPageItem);
                    }

                    if(pageForm.getView().equals(TransactionView.INPUT)) {
                        highestInputIndex = Math.min(max, pageForm.getPageEnd());
                        fetchThisAndInputBlockTransactions(pageForm.getPageStart(), Math.min(max, pageForm.getPageEnd()));
                    } else {
                        highestOutputIndex = Math.min(max, pageForm.getPageEnd());
                        fetchOutputBlockTransactions(pageForm.getPageStart(), Math.min(max, pageForm.getPageEnd()));
                    }

                    setTreeSelection(pageForm.getView(), pageForm.getPageStart());
                    Platform.runLater(() -> {
                        txtree.scrollTo(pageForm.getPageStart());
                        refreshTxHex();
                    });
                }
            } else {
                try {
                    Node node = transactionForm.getContents();
                    txpane.getChildren().clear();
                    txpane.getChildren().add(node);

                    if (node instanceof Parent) {
                        Parent parent = (Parent) node;
                        txhex.getStylesheets().clear();
                        txhex.getStylesheets().addAll(parent.getStylesheets());

                        selectedInputIndex = -1;
                        selectedOutputIndex = -1;
                        if (transactionForm instanceof InputForm) {
                            InputForm inputForm = (InputForm) transactionForm;
                            selectedInputIndex = inputForm.getTransactionInput().getIndex();
                        } else if (transactionForm instanceof OutputForm) {
                            OutputForm outputForm = (OutputForm) transactionForm;
                            selectedOutputIndex = outputForm.getTransactionOutput().getIndex();
                        }

                        Platform.runLater(this::refreshTxHex);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Can't find pane", e);
                }
            }
        });

        if(initialView != null) {
            setTreeSelection(initialView, initialIndex);
        } else {
            txtree.getSelectionModel().select(txtree.getRoot());
        }
    }

    private TreeItem<TransactionForm> createInputTreeItem(int inputIndex) {
        TransactionInput txInput = transaction.getInputs().get(inputIndex);
        PSBTInput psbtInput = null;
        if (psbt != null && psbt.getPsbtInputs().size() > txInput.getIndex()) {
            psbtInput = psbt.getPsbtInputs().get(txInput.getIndex());
        }
        InputForm inputForm = (psbt != null ? new InputForm(psbt, psbtInput) : (blockTransaction != null ? new InputForm(blockTransaction, txInput) : new InputForm(transaction, txInput)));
        return new TreeItem<>(inputForm);
    }

    private TreeItem<TransactionForm> createOutputTreeItem(int outputIndex) {
        TransactionOutput txOutput = transaction.getOutputs().get(outputIndex);
        PSBTOutput psbtOutput = null;
        if (psbt != null && psbt.getPsbtOutputs().size() > txOutput.getIndex()) {
            psbtOutput = psbt.getPsbtOutputs().get(txOutput.getIndex());
        }
        OutputForm outputForm = (psbt != null ? new OutputForm(psbt, psbtOutput) : (blockTransaction != null ? new OutputForm(blockTransaction, txOutput) : new OutputForm(transaction, txOutput)));
        return new TreeItem<>(outputForm);
    }

    public void setTreeSelection(TransactionView view, Integer index) {
        select(txtree.getRoot(), view, index);
    }

    private void select(TreeItem<TransactionForm> treeItem, TransactionView view, Integer index) {
        if(treeItem.getValue().getView().equals(view)) {
            if(view.equals(TransactionView.INPUT) || view.equals(TransactionView.OUTPUT)) {
                IndexedTransactionForm txForm = (IndexedTransactionForm)treeItem.getValue();
                if(txForm.getIndex() == index) {
                    txtree.getSelectionModel().select(treeItem);
                    return;
                }
            } else {
                txtree.getSelectionModel().select(treeItem);
                return;
            }
        }

        for(TreeItem<TransactionForm> childItem : treeItem.getChildren()) {
            select(childItem, view, index);
        }
    }

    void refreshTxHex() {
        //TODO: Handle large transactions like efd513fffbbc2977c2d3933dfaab590b5cab5841ee791b3116e531ac9f8034ed better by not replacing text
        txhex.clear();

        String hex = "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            transaction.bitcoinSerializeToStream(baos);
            hex = Utils.bytesToHex(baos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Can't happen");
        }

        int cursor = 0;

        //Version
        cursor = addText(hex, cursor, 8, "version");

        if (transaction.hasWitnesses()) {
            //Segwit marker
            cursor = addText(hex, cursor, 2, "segwit-marker");
            //Segwit flag
            cursor = addText(hex, cursor, 2, "segwit-flag");
        }

        //Number of inputs
        VarInt numInputs = new VarInt(transaction.getInputs().size());
        cursor = addText(hex, cursor, numInputs.getSizeInBytes() * 2, "num-inputs");

        //Inputs
        for (int i = 0; i < transaction.getInputs().size(); i++) {
            if(i == highestInputIndex) {
                txhex.append("...", "");
            }

            TransactionInput input = transaction.getInputs().get(i);
            boolean skip = (i >= highestInputIndex);
            cursor = addText(hex, cursor, 32 * 2, "input-" + getIndexedStyleClass(i, selectedInputIndex, "hash"), skip);
            cursor = addText(hex, cursor, 4 * 2, "input-" + getIndexedStyleClass(i, selectedInputIndex, "index"), skip);
            VarInt scriptLen = new VarInt(input.getScriptBytes().length);
            cursor = addText(hex, cursor, scriptLen.getSizeInBytes() * 2, "input-" + getIndexedStyleClass(i, selectedInputIndex, "sigscript-length"), skip);
            cursor = addText(hex, cursor, (int) scriptLen.value * 2, "input-" + getIndexedStyleClass(i, selectedInputIndex, "sigscript"), skip);
            cursor = addText(hex, cursor, 4 * 2, "input-" + getIndexedStyleClass(i, selectedInputIndex, "sequence"), skip);
        }

        //Number of outputs
        VarInt numOutputs = new VarInt(transaction.getOutputs().size());
        cursor = addText(hex, cursor, numOutputs.getSizeInBytes() * 2, "num-outputs");

        //Outputs
        for (int i = 0; i < transaction.getOutputs().size(); i++) {
            if(i == highestOutputIndex) {
                txhex.append("...", "");
            }

            TransactionOutput output = transaction.getOutputs().get(i);
            boolean skip = (i >= highestOutputIndex);
            cursor = addText(hex, cursor, 8 * 2, "output-" + getIndexedStyleClass(i, selectedOutputIndex, "value"), skip);
            VarInt scriptLen = new VarInt(output.getScriptBytes().length);
            cursor = addText(hex, cursor, scriptLen.getSizeInBytes() * 2, "output-" + getIndexedStyleClass(i, selectedOutputIndex, "pubkeyscript-length"), skip);
            cursor = addText(hex, cursor, (int) scriptLen.value * 2, "output-" + getIndexedStyleClass(i, selectedOutputIndex, "pubkeyscript"), skip);
        }

        if (transaction.hasWitnesses()) {
            for (int i = 0; i < transaction.getInputs().size(); i++) {
                if(i == highestInputIndex) {
                    txhex.append("...", "");
                }

                TransactionInput input = transaction.getInputs().get(i);
                boolean skip = (i >= highestInputIndex);
                if (input.hasWitness()) {
                    TransactionWitness witness = input.getWitness();
                    VarInt witnessCount = new VarInt(witness.getPushCount());
                    cursor = addText(hex, cursor, witnessCount.getSizeInBytes() * 2, "witness-" + getIndexedStyleClass(i, selectedInputIndex, "count"), skip);
                    for (byte[] push : witness.getPushes()) {
                        VarInt witnessLen = new VarInt(push.length);
                        cursor = addText(hex, cursor, witnessLen.getSizeInBytes() * 2, "witness-" + getIndexedStyleClass(i, selectedInputIndex, "length"), skip);
                        cursor = addText(hex, cursor, (int) witnessLen.value * 2, "witness-" + getIndexedStyleClass(i, selectedInputIndex, "data"), skip);
                    }
                }
            }
        }

        //Locktime
        cursor = addText(hex, cursor, 8, "locktime");

        if(cursor != hex.length()) {
            throw new IllegalStateException("Cursor position does not match transaction serialisation " + cursor + ": " + hex.length());
        }
    }

    private void fetchThisAndInputBlockTransactions(int indexStart, int indexEnd) {
        if(AppController.isOnline() && indexStart < transaction.getInputs().size()) {
            Set<Sha256Hash> references = new HashSet<>();
            if (psbt == null) {
                references.add(transaction.getTxId());
            }

            int maxIndex = Math.min(transaction.getInputs().size(), indexEnd);
            for(int i = indexStart; i < maxIndex; i++) {
                TransactionInput input = transaction.getInputs().get(i);
                if(!input.isCoinBase()) {
                    references.add(input.getOutpoint().getHash());
                }
            }

            if(references.isEmpty()) {
                return;
            }

            ElectrumServer.TransactionReferenceService transactionReferenceService = new ElectrumServer.TransactionReferenceService(references);
            transactionReferenceService.setOnSucceeded(successEvent -> {
                Map<Sha256Hash, BlockTransaction> transactionMap = transactionReferenceService.getValue();
                BlockTransaction thisBlockTx = null;
                Map<Sha256Hash, BlockTransaction> inputTransactions = new HashMap<>();
                for (Sha256Hash txid : transactionMap.keySet()) {
                    BlockTransaction blockTx = transactionMap.get(txid);
                    if (txid.equals(transaction.getTxId())) {
                        thisBlockTx = blockTx;
                    } else {
                        inputTransactions.put(txid, blockTx);
                        references.remove(txid);
                    }
                }

                references.remove(transaction.getTxId());
                if (!references.isEmpty()) {
                    System.out.println("Failed to retrieve all referenced input transactions, aborting transaction fetch");
                    return;
                }

                final BlockTransaction blockTx = thisBlockTx;
                Platform.runLater(() -> {
                    EventManager.get().post(new BlockTransactionFetchedEvent(transaction.getTxId(), blockTx, inputTransactions, indexStart, maxIndex));
                });
            });
            transactionReferenceService.setOnFailed(failedEvent -> {
                failedEvent.getSource().getException().printStackTrace();
            });
            transactionReferenceService.start();
        }
    }

    private void fetchOutputBlockTransactions(int indexStart, int indexEnd) {
        if(AppController.isOnline() && psbt == null && indexStart < transaction.getOutputs().size()) {
            int maxIndex = Math.min(transaction.getOutputs().size(), indexEnd);
            ElectrumServer.TransactionOutputsReferenceService transactionOutputsReferenceService = new ElectrumServer.TransactionOutputsReferenceService(transaction, indexStart, maxIndex);
            transactionOutputsReferenceService.setOnSucceeded(successEvent -> {
                List<BlockTransaction> outputTransactions = transactionOutputsReferenceService.getValue();
                Platform.runLater(() -> {
                    EventManager.get().post(new BlockTransactionOutputsFetchedEvent(transaction.getTxId(), outputTransactions, indexStart, maxIndex));
                });
            });
            transactionOutputsReferenceService.setOnFailed(failedEvent -> {
                failedEvent.getSource().getException().printStackTrace();
            });
            transactionOutputsReferenceService.start();
        }
    }

    private String getIndexedStyleClass(int iterableIndex, int selectedIndex, String styleClass) {
        if (selectedIndex == -1 || selectedIndex == iterableIndex) {
            return styleClass;
        }

        return "other";
    }

    private int addText(String hex, int cursor, int length, String styleClass) {
        return addText(hex, cursor, length, styleClass, false);
    }

    private int addText(String hex, int cursor, int length, String styleClass, boolean skip) {
        if(!skip) {
            txhex.append(hex.substring(cursor, cursor + length), styleClass);
        }

        return cursor + length;
    }

    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;

        initializeView();
    }

    public void setPSBT(PSBT psbt) {
        this.psbt = psbt;
    }

    public void setBlockTransaction(BlockTransaction blockTransaction) {
        this.blockTransaction = blockTransaction;
    }

    public void setInitialView(TransactionView initialView, Integer initialIndex) {
        this.initialView = initialView;
        this.initialIndex = initialIndex;
    }

    @Subscribe
    public void transactionChanged(TransactionChangedEvent event) {
        if (event.getTransaction().equals(transaction)) {
            refreshTxHex();
            txtree.refresh();
        }
    }

    @Subscribe
    public void tabSelected(TransactionTabSelectedEvent event) {

    }

    @Subscribe
    public void tabChanged(TransactionTabChangedEvent event) {
        transactionMasterDetail.setShowDetailNode(event.isTxHexVisible());
    }

    @Subscribe
    public void blockTransactionFetched(BlockTransactionFetchedEvent event) {
        if (event.getTxId().equals(transaction.getTxId())) {
            setBlockTransaction(txtree.getRoot(), event);
        }
    }

    private void setBlockTransaction(TreeItem<TransactionForm> treeItem, BlockTransactionFetchedEvent event) {
        TransactionForm form = treeItem.getValue();
        form.setBlockTransaction(event.getBlockTransaction());
        if(form.getInputTransactions() == null) {
            form.setInputTransactions(event.getInputTransactions());
        } else {
            form.getInputTransactions().putAll(event.getInputTransactions());
        }

        for (TreeItem<TransactionForm> childItem : treeItem.getChildren()) {
            setBlockTransaction(childItem, event);
        }
    }

    @Subscribe
    public void blockTransactionOutputsFetched(BlockTransactionOutputsFetchedEvent event) {
        if (event.getTxId().equals(transaction.getTxId())) {
            setBlockTransactionOutputs(txtree.getRoot(), event);
        }
    }

    private void setBlockTransactionOutputs(TreeItem<TransactionForm> treeItem, BlockTransactionOutputsFetchedEvent event) {
        TransactionForm form = treeItem.getValue();
        if(form.getOutputTransactions() == null) {
            form.setOutputTransactions(event.getOutputTransactions());
        } else {
            for(int i = 0; i < event.getOutputTransactions().size(); i++) {
                BlockTransaction outputTransaction = event.getOutputTransactions().get(i);
                if(outputTransaction != null) {
                    form.getOutputTransactions().set(i, outputTransaction);
                }
            }
        }

        for (TreeItem<TransactionForm> childItem : treeItem.getChildren()) {
            setBlockTransactionOutputs(childItem, event);
        }
    }
}