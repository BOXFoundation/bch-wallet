import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neemre.btcdcli4j.core.client.BtcdClient;
import com.neemre.btcdcli4j.core.client.BtcdClientImpl;
import com.neemre.btcdcli4j.core.domain.*;
import com.neemre.btcdcli4j.daemon.BtcdDaemon;
import com.neemre.btcdcli4j.daemon.BtcdDaemonImpl;
import com.neemre.btcdcli4j.daemon.event.WalletListener;
import javafx.util.Pair;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

/**
 * A list of examples demonstrating the use of <i>bitcoind</i>'s wallet RPCs (via the JSON-RPC
 * API) using wrapper https://github.com/priiduneemre/btcd-cli4j.
 */
public class BchRpcClient {

    // NOTE: do the following before running
    // 1) get some testnet bitcoins from mining or faucet into default account
    // 2) start bitcoind locally in regtest mode

    private static final String BET_ADDRESS = "bchreg:qpafx53fxam6uqrmelruhpr95x2rkvw59v62dvckqm";
    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String HMAC_SHA512 = "HmacSHA512";
    private static final String SERVER_SECRET = "293d5d2ddd365f54759283a8097ab2640cbe6f8864adc2b1b31e65c14c999f04";
    // 1 satoshi/byte
    private static final BigDecimal BITCOIN_PER_BYTE = BigDecimal.valueOf(1e-5);
    // TODO: calculate tx size
    private static final BigDecimal TX_SIZE_IN_BYTE = BigDecimal.valueOf(250);

    // betting
    private static final BigDecimal WIN_MULTIPLIER = BigDecimal.valueOf(1.98);
    private static final Integer WIN_MAX_NUM = Integer.valueOf(32678);

    // roll dice based on client value and server secret
    private static Integer rollDice(String value, String secret) throws RuntimeException {
        try {
            // step 1: sha512 client value, with server secret as seed
            Mac sha512HMAC = Mac.getInstance(HMAC_SHA512);
            // seed with server secret
            SecretKeySpec secretSpec = new SecretKeySpec(secret.getBytes(DEFAULT_ENCODING), HMAC_SHA512);
            sha512HMAC.init(secretSpec);
            byte[] digest = sha512HMAC.doFinal(value.getBytes(DEFAULT_ENCODING));
            System.out.println(DatatypeConverter.printHexBinary(digest));

            // step 2: get the first 16 bits, i.e., 2 bytes
            byte[] first2Bytes = Arrays.copyOfRange(digest, 0, 2);
            System.out.println(DatatypeConverter.printHexBinary(first2Bytes));
            // convert to decimal
            ByteBuffer bb = ByteBuffer.wrap(first2Bytes);
            // convert signed short to unsigned
            return Short.toUnsignedInt(bb.getShort());
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException | InvalidKeyException ex) {
            ex.printStackTrace();
            throw new RuntimeException("Error in calculating HMAC", ex);
        }
    }

    private static Pair<List<OutputOverview> /* coins to spend */, BigDecimal /* change */> selectUnspentCoins(List<Output> unspentCoins, BigDecimal totalPayment) throws RuntimeException {
        // sort coins based on descending amount
        List<Output> coins = unspentCoins.stream().sorted(Comparator.comparing(Output::getAmount).reversed()).collect(Collectors.toList());

        BigDecimal totalCoin = BigDecimal.ZERO;
        List<OutputOverview> selectedCoins = new ArrayList<>();
        for (final Output coin : coins) {
            selectedCoins.add(coin);
            totalCoin = totalCoin.add(coin.getAmount());
            if (totalCoin.compareTo(totalPayment) >= 0) {
                return new Pair<>(selectedCoins, totalCoin.subtract(totalPayment));
            }
        }
        throw new RuntimeException("Insufficient fund: " + totalCoin + " < " + totalPayment);
    }

    private static Optional<Pair<BigDecimal /* bet amount */, Integer /* vout */>> getBet(List<RawOutput> vout) {
        for (RawOutput out : vout) {
            if (out.getScriptPubKey().getAddresses().contains(BET_ADDRESS)) {
                return Optional.of(new Pair<>(out.getValue(), out.getN()));
            }
        }
        return Optional.empty();
    }

    public static void main(String[] args) throws Exception {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        CloseableHttpClient httpProvider = HttpClients.custom().setConnectionManager(cm)
                .build();
        Properties nodeConfig = new Properties();
        InputStream is = new BufferedInputStream(new FileInputStream("src/main/resources/node_config.properties"));
        nodeConfig.load(is);
        is.close();

        BtcdClient client = new BtcdClientImpl(httpProvider, nodeConfig);


        BtcdDaemon daemon = new BtcdDaemonImpl(client);
        System.out.printf("Node configuration passed to & used by the daemon: '%s'\n", daemon.getNodeConfig());

        System.out.printf("The daemon reported a block listener count of: %s\n", daemon.countWalletListeners());
        daemon.addWalletListener(new WalletListener() {
            @Override
            public void walletChanged(Transaction transaction) {
                System.out.printf("Wallet transaction changed! (Event details: '%s')\n",
                        transaction);
                try {
                    //String txId = "5950b059bc7495f10161a989bb7ee628bededcb31c082fce4222474fe372b00e";
                    String txId = transaction.getTxId();
                    // get detailed transaction
                    RawTransaction rawTx = (RawTransaction) client.getRawTransaction(txId, 1);

                    //// fetch sender address
                    // get vout
                    List<RawOutput> vOut = rawTx.getVOut();
                    Optional<Pair<BigDecimal, Integer>> optionalBet = getBet(vOut);
                    if (!optionalBet.isPresent()) {
                        System.out.println("Not a betting tx");
                        return;
                    }
                    BigDecimal betAmount = optionalBet.get().getKey();
                    Integer betVOut = optionalBet.get().getValue();
                    System.out.println("Bet received: amount " + betAmount + ", vout " + betVOut);

                    // roll the dice
                    Integer roll = rollDice(txId + betVOut, SERVER_SECRET);
                    if (roll >= WIN_MAX_NUM) {
                        System.out.println("Tx " + txId + " loss: roll " + roll + " >= " + WIN_MAX_NUM);
                        return;
                    }
                    System.out.println("Tx " + txId + " win: roll " + roll + " < " + WIN_MAX_NUM);

                    // // pay winner
                    BigDecimal payout = betAmount.multiply(WIN_MULTIPLIER);

                    // get sender address
                    List<RawInput> vIn = rawTx.getVIn();
                    RawInput senderVIn = vIn.get(0);
                    System.out.println("TxId: " + senderVIn.getTxId() + ", vout: " + senderVIn.getVOut());
                    RawTransaction parentRawTx = (RawTransaction) client.getRawTransaction(senderVIn.getTxId(), 1);
                    RawOutput parentVOut = parentRawTx.getVOut().get(senderVIn.getVOut());
                    String senderAddress = parentVOut.getScriptPubKey().getAddresses().get(0);
                    System.out.println("sender address: " + senderAddress);

                    // get tx fee
                    BigDecimal txFee = TX_SIZE_IN_BYTE.multiply(BITCOIN_PER_BYTE);

                    List<Output> unspentCoins = client.listUnspent();
                    Pair<List<OutputOverview>, BigDecimal> result = selectUnspentCoins(unspentCoins, payout.subtract(betAmount).add(txFee));
                    List<OutputOverview> coinsToSpend = result.getKey();
                    // include betting payment
                    coinsToSpend.add(new OutputOverview(txId, betVOut));
                    coinsToSpend.forEach(System.out::println);
                    BigDecimal coinChange = result.getValue();

                    String rawTxHex = client.createRawTransaction(coinsToSpend,
                            new HashMap<String, BigDecimal>() {{
                                put(senderAddress, payout);
                                put(client.getRawChangeAddress(), coinChange);
                            }});

                    SignatureResult signatureResult = client.signRawTransaction(rawTxHex);
                    // pretty json format
                    ObjectMapper mapper = new ObjectMapper();
                    Object json = mapper.readValue(mapper.writeValueAsString(client.decodeRawTransaction(rawTxHex)), RawTransactionOverview.class);
                    String indented = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
                    System.out.println(indented);

                    System.out.println("Tx sent: " + client.sendRawTransaction(signatureResult.getHex()));
                } catch (Exception e) {
                    System.out.println(e);
                }
            }
        });
        System.out.printf("The daemon reported a block listener count of: %s\n", daemon.countWalletListeners());
        Thread.sleep(60000);
        System.out.println("-----------------------------------------------------------------------------------------");
        daemon.shutdown();
        System.out.println("-----------------------------------------------------------------------------------------");
        Thread.sleep(10000);
    }
}