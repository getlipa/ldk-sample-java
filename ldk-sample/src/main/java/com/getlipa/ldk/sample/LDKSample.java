package com.getlipa.ldk.sample;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.script.Script;
import org.bouncycastle.util.encoders.Hex;
import org.ldk.batteries.ChannelManagerConstructor;
import org.ldk.batteries.NioPeerHandler;
import org.ldk.enums.ConfirmationTarget;
import org.ldk.enums.Currency;
import org.ldk.enums.Network;
import org.ldk.structs.BroadcasterInterface;
import org.ldk.structs.ChainMonitor;
import org.ldk.structs.ChannelManager;
import org.ldk.structs.ChannelMonitor;
import org.ldk.structs.ChannelMonitorUpdate;
import org.ldk.structs.Event;
import org.ldk.structs.Filter;
import org.ldk.structs.Invoice;
import org.ldk.structs.KeysManager;
import org.ldk.structs.Option_C2Tuple_usizeTransactionZZ;
import org.ldk.structs.Option_u64Z;
import org.ldk.structs.OutPoint;
import org.ldk.structs.Persist;
import org.ldk.structs.Result_InvoiceSignOrCreationErrorZ;
import org.ldk.structs.Result_NoneChannelMonitorUpdateErrZ;
import org.ldk.structs.Result_TransactionNoneZ;
import org.ldk.structs.TxOut;
import org.ldk.structs.UserConfig;
import org.ldk.structs.UtilMethods;
import org.ldk.structs.WatchedOutput;
import org.ldk.util.TwoTuple;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.bitcoinj.core.NetworkParameters.ID_REGTEST;

public class LDKSample {

    private final String SEED = Env.get("LDK_SEED");

    private final String PEER_PUBKEY = Env.get("LDK_PEER_PUBKEY");
    private final String PEER_HOST = Env.get("LDK_PEER_HOST");
    private final int PEER_PORT = Integer.parseInt(Env.get("LDK_PEER_PORT"));

    private final String refundAddress = "";

    private final ChainBackend chainBackend = new BitcoinCoreChainBackend(NetworkParameters.fromID(ID_REGTEST));

    public static void main(String[] args) throws Exception {
        LDKSample app = new LDKSample();
        app.start();
    }

    static class WatchedTransaction {
        public final byte[] id;

        public final byte[] scriptPubKey;

        public WatchedTransaction(final byte[] id, final byte[] scriptPubKey) {
            this.id = id;
            this.scriptPubKey = scriptPubKey;
        }
    }

    void start() throws Exception {
        // Step 1
        final var feeEstimator = org.ldk.structs.FeeEstimator.new_impl(confirmation_target -> {
            return 12500; // TODO
        });

        // Step 2
        final var logger = org.ldk.structs.Logger.new_impl(System.out::println);

        // Step 3
        final BroadcasterInterface txBroadcaster = BroadcasterInterface.new_impl(chainBackend::publish);

        // Step 4 - SKIPPED / OPTIONAL

        // Step 5
        final var persist = Persist.new_impl(new Persist.PersistInterface() {

            @Override
            public Result_NoneChannelMonitorUpdateErrZ persist_new_channel(final OutPoint id, final ChannelMonitor data) {
                System.out.printf("Persist new %s / %s%n", Hex.toHexString(id.to_channel_id()), Hex.toHexString(reverse(id.get_txid())));
                persist(id, data.write());
                return Result_NoneChannelMonitorUpdateErrZ.ok();
            }

            @Override
            public Result_NoneChannelMonitorUpdateErrZ update_persisted_channel(final OutPoint id, final ChannelMonitorUpdate update, final ChannelMonitor data) {
                System.out.printf("Persist existing %s / %s%n", Hex.toHexString(id.to_channel_id()), Hex.toHexString(reverse(id.get_txid())));
                persist(id, data.write());
                return Result_NoneChannelMonitorUpdateErrZ.ok();
            }

            private void persist(OutPoint id, byte[] data) {
                final var identifier = String.format("channels/%s.dat", Hex.toHexString(id.to_channel_id()));
                write(identifier, data);
            }
        });

        // Step 7
        final var relevantTxs = new ArrayList<WatchedTransaction>();
        final var relevantOutputs = new ArrayList<WatchedOutput>();
        final Filter filter = Filter.new_impl(new Filter.FilterInterface() {
            @Override
            public void register_tx(byte[] txid, byte[] script_pubkey) {
                relevantTxs.add(new WatchedTransaction(txid, script_pubkey));
            }

            @Override
            public Option_C2Tuple_usizeTransactionZZ register_output(WatchedOutput output) {
                relevantOutputs.add(output);
                return Option_C2Tuple_usizeTransactionZZ.none();
            }
        });

        // Step 8
        final ChainMonitor chainMonitor = ChainMonitor.of(filter, txBroadcaster, logger, feeEstimator, persist);

        // Step 9
        final var seed = Hex.decode(SEED);
        final var startupTime = System.currentTimeMillis();
        final var keyManager = KeysManager.of(
                seed,
                startupTime / 1000,
                (int) (startupTime * 1000)
        );

        // Step 10
        final var directory = new File("data/channels");
        var channelMonitors = new byte[][]{};
        if (directory.exists() && directory.isDirectory()) {
            channelMonitors = Arrays.stream(directory.listFiles())
                    .map(File::toPath)
                    .map(path -> {
                        try {
                            return (Files.readAllBytes(path));
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toArray(byte[][]::new);
        }

        // Step 11
        final var channelManagerFile = new File("data/channel-manager.dat");
        byte[] serializedChannelManager = null;
        if (channelManagerFile.exists() && channelManagerFile.isFile()) {
            serializedChannelManager = Files.readAllBytes(channelManagerFile.toPath());
        }

        ChannelManagerConstructor channelManagerConstructor;
        if (serializedChannelManager == null) {
            final var currentBlockHeight = chainBackend.blockHeight(); //126;
            final var latestBlockHash = chainBackend.blockHash(currentBlockHeight);
            channelManagerConstructor = new ChannelManagerConstructor(
                    Network.LDKNetwork_Regtest,
                    UserConfig.with_default(),
                    latestBlockHash,
                    currentBlockHeight,
                    keyManager.as_KeysInterface(),
                    feeEstimator,
                    chainMonitor,
                    null,
                    txBroadcaster,
                    logger
            );
        } else {
            channelManagerConstructor = new ChannelManagerConstructor(
                    serializedChannelManager,
                    channelMonitors,
                    keyManager.as_KeysInterface(),
                    feeEstimator,
                    chainMonitor,
                    filter,
                    null,
                    txBroadcaster,
                    logger
            );
        }
        final ChannelManager channelManager = channelManagerConstructor.channel_manager;

        // Step 6
        final var channelManagerPersister = new ChannelManagerConstructor.ChannelManagerPersister() {

            @Override
            public void handle_event(final Event e) {
                final var params = NetworkParameters.fromID(ID_REGTEST);
                if (e instanceof Event.FundingGenerationReady) {
                    var event = (Event.FundingGenerationReady) e;
                    final var transaction = new Transaction(params);
                    final var input = new TransactionInput(params, transaction, new byte[0]);
                    input.setWitness(new TransactionWitness(2));
                    input.getWitness().setPush(0, new byte[]{0x1});
                    transaction.addInput(input);
                    final var script = new Script(event.output_script);
                    final var value = Coin.SATOSHI.multiply(event.channel_value_satoshis);
                    transaction.addOutput(value, script);
                    channelManager.funding_transaction_generated(event.temporary_channel_id, transaction.bitcoinSerialize());
                } else if (e instanceof Event.PaymentReceived) {
                    var event = (Event.PaymentReceived) e;
                    System.out.printf("Payment of %s SAT received.%n", event.amt);
                    channelManager.claim_funds(event.payment_preimage);
                } else if (e instanceof Event.PaymentSent) {
                    var event = (Event.PaymentSent) e;
                    System.out.printf("Payment with preimage '%s' sent.%n", Hex.toHexString(event.payment_preimage));
                } else if (e instanceof Event.PaymentFailed) {
                    var event = (Event.PaymentFailed) e;
                    System.out.printf("Payment with payment hash '%s' failed.%n", Hex.toHexString(event.payment_hash));
                } else if (e instanceof Event.PendingHTLCsForwardable) {
                    var event = (Event.PendingHTLCsForwardable) e;
                    channelManager.process_pending_htlc_forwards();
                } else if (e instanceof Event.SpendableOutputs) {
                    var event = (Event.SpendableOutputs) e;
                    final var tx = keyManager.spend_spendable_outputs(
                            event.outputs,
                            new TxOut[]{},
                            Hex.decode(refundAddress),
                            feeEstimator.get_est_sat_per_1000_weight(ConfirmationTarget.LDKConfirmationTarget_HighPriority)
                    );
                    if (tx instanceof Result_TransactionNoneZ.Result_TransactionNoneZ_OK) {
                        chainBackend.publish(((Result_TransactionNoneZ.Result_TransactionNoneZ_OK) tx).res);
                    }
                }
            }

            @Override
            public void persist_manager(final byte[] bytes) {
                write("channel-manager.dat", bytes);
            }
        };

        // Step 12 - TODO
        // Retrieve transaction IDs to check the chain for un-confirmation.
        byte[][] relevant_txids_1 = channelManager.as_Confirm().get_relevant_txids();
        byte[][] relevant_txids_2 = chainMonitor.as_Confirm().get_relevant_txids();
        final var list = new ArrayList<>(List.of(relevant_txids_1));
        list.addAll(List.of(relevant_txids_2));
        list.stream()
                .filter(txid -> !chainBackend.isConfirmed(txid))
                .forEach(txid -> {
                    channelManager.as_Confirm().transaction_unconfirmed(txid);
                    chainMonitor.as_Confirm().transaction_unconfirmed(txid);
                });

        checkBlockchain(relevantTxs, channelManager, chainMonitor);
        channelManagerConstructor.chain_sync_completed(channelManagerPersister);

        // Step 13 - DONE
        final NioPeerHandler peerHandler = channelManagerConstructor.nio_peer_handler;
        final int port = 9730;
        peerHandler.bind_listener(new InetSocketAddress("0.0.0.0", port));
        System.out.printf("Node started on port %d. PubKey is %s%n", port, Hex.toHexString(channelManager.get_our_node_id()));

        final var peerPubKey = Hex.decode(PEER_PUBKEY);

        System.out.printf("Currently having %d channels, %d of them are ready to use.%n", channelManager.list_channels().length, channelManager.list_usable_channels().length);

        peerHandler.connect(
                peerPubKey,
                new InetSocketAddress(PEER_HOST, PEER_PORT),
                10000
        );


        createInvoice(channelManager, keyManager);

        while (true) {
            checkBlockchain(relevantTxs, channelManager, chainMonitor);
            System.out.printf("Currently having %d channels, %d of them are ready to use.%n", channelManager.list_channels().length, channelManager.list_usable_channels().length);
            Thread.sleep(10000);
        }
    }

    void checkBlockchain(List<WatchedTransaction> relevantTxs, ChannelManager channelManager, ChainMonitor chainMonitor) {
        relevantTxs.stream()
                .map(watchedTransaction -> chainBackend.determineBlockInfo(watchedTransaction.id))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(txBlockInfo -> txBlockInfo.block))
                .entrySet()
                .stream()
                .peek(entry -> {
                    final var block = entry.getKey();
                    block.height = chainBackend.blockHeight(block.hash);
                })
                .sorted(Comparator.comparing(entry -> entry.getKey().height))
                .forEach(entry -> {
                    final var block = entry.getKey();
                    final var txData = new TwoTuple[entry.getValue().size()];
                    var i = 0;
                    for (final var txInfo : entry.getValue()) {
                        txData[i] = new TwoTuple<>(chainBackend.determineBlockIndex(block.hash, txInfo.id), txInfo.data);
                        i++;
                    }
                    final var blockHeader = chainBackend.blockHeader(block.hash);
                    final var blockHeight = block.height;
                    channelManager.as_Confirm().transactions_confirmed(blockHeader, txData, blockHeight);
                    chainMonitor.as_Confirm().transactions_confirmed(blockHeader, txData, blockHeight);
                });

        final var currentBlockHeight = chainBackend.blockHeight();
        final var latestBlockHash = chainBackend.blockHash(currentBlockHeight);
        final var latestBlockHeader = chainBackend.blockHeader(latestBlockHash);
        channelManager.as_Confirm().best_block_updated(latestBlockHeader, currentBlockHeight);
        chainMonitor.as_Confirm().best_block_updated(latestBlockHeader, currentBlockHeight);
    }

    void createInvoice(ChannelManager channelManager, KeysManager keysManager) {
        final var amountMsat = 10000L;
        Result_InvoiceSignOrCreationErrorZ result = UtilMethods.create_invoice_from_channelmanager(
                channelManager,
                keysManager.as_KeysInterface(),
                Currency.LDKCurrency_Regtest,
                Option_u64Z.some(amountMsat),
                "test invoice"
        );
        assert result instanceof Result_InvoiceSignOrCreationErrorZ.Result_InvoiceSignOrCreationErrorZ_OK;
        Invoice invoice = ((Result_InvoiceSignOrCreationErrorZ.Result_InvoiceSignOrCreationErrorZ_OK) result).res;
        System.out.printf("Invoice: %s%n", invoice.to_str());
    }

    static byte[] reverse(final byte input[]) {
        final var output = new byte[input.length + 1];
        for (var i = 0; i < input.length; i++) {
            output[input.length - i] = input[i];
        }
        return output;
    }

    static void write(final String identifier, final byte[] data) {
        try {
            final var fileName = String.format("data/%s", identifier);
            final var file = new File(fileName);
            if (file.exists() || file.getParentFile().mkdirs() && file.createNewFile()) {
                final var out = new FileOutputStream(fileName);
                out.write(data);
                out.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
