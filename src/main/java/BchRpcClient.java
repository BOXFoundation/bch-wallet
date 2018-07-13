import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Properties;

import com.neemre.btcdcli4j.core.client.BtcdClient;
import com.neemre.btcdcli4j.core.client.BtcdClientImpl;
import com.neemre.btcdcli4j.core.domain.*;
import com.neemre.btcdcli4j.daemon.BtcdDaemon;
import com.neemre.btcdcli4j.daemon.BtcdDaemonImpl;
import com.neemre.btcdcli4j.daemon.event.BlockListener;
import com.neemre.btcdcli4j.daemon.event.WalletListener;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

/**
 * A list of examples demonstrating the use of <i>bitcoind</i>'s wallet RPCs (via the JSON-RPC
 * API) using wrapper https://github.com/priiduneemre/btcd-cli4j.
 */
public class BchRpcClient {

    // NOTE: do the following before u run
    // 1) get some testnet bitcoins from faucet into default account
    // 2) start bitcoind locally

    public static void main(String[] args) throws Exception {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        CloseableHttpClient httpProvider = HttpClients.custom().setConnectionManager(cm)
                .build();
        Properties nodeConfig = new Properties();
        InputStream is = new BufferedInputStream(new FileInputStream("src/main/resources/node_config.properties"));
        nodeConfig.load(is);
        is.close();

        BtcdClient client = new BtcdClientImpl(httpProvider, nodeConfig);

        RawTransaction rawTx = (RawTransaction) client.getRawTransaction("b1dd43ad7063f18f31a57fa8921dbe194f91747ac299420efdfcef96965051c9", 1);

        //// fetch sender address
        // get utxo
        List<RawInput> vIn = rawTx.getVIn();
        RawInput senderVIn = vIn.get(0);
        System.out.println("TxId: " + senderVIn.getTxId() + ", vout: " + senderVIn.getVOut());

        RawTransaction parentRawTx = (RawTransaction) client.getRawTransaction(senderVIn.getTxId(), 1);
        RawOutput parentVOut = parentRawTx.getVOut().get(senderVIn.getVOut());
        System.out.println("sender address: " + parentVOut.getScriptPubKey().getAddresses().get(0));

//        BtcdDaemon daemon = new BtcdDaemonImpl(client);
        BtcdDaemon daemon = new BtcdDaemonImpl(5158, 5159, 5160);
        System.out.printf("Node configuration passed to & used by the daemon: '%s'\n", daemon.getNodeConfig());

        System.out.printf("The daemon reported a block listener count of: %s\n", daemon.countBlockListeners());
        daemon.addBlockListener(new BlockListener() {
            @Override
            public void blockDetected(Block block) {
                System.out.printf("New block detected! (Event details: '%s')\n", block);
            }
        });
        daemon.addWalletListener(new WalletListener() {
            @Override
            public void walletChanged(Transaction transaction) {
                System.out.printf("Wallet transaction changed! (Event details: '%s')\n",
                        transaction);
            }
        });
        System.out.printf("The daemon reported a block listener count of: %s\n", daemon.countBlockListeners());

        Thread.sleep(60000);
        System.out.println("-----------------------------------------------------------------------------------------");
        daemon.shutdown();
        System.out.println("-----------------------------------------------------------------------------------------");
        Thread.sleep(10000);


        client.listAccounts();

        // setup accounts
        // create account "buyer"
        String buyerAddr = client.getAccountAddress("buyer");
        System.out.println("Buyer's address: " + buyerAddr);
        // create account "seller"
        String sellerAddr = client.getAccountAddress("seller");
        System.out.println("Seller's address: " + sellerAddr);
        // create account "ESCROW"
        client.getAccountAddress("ESCROW");
        // check default account's balance
        BigDecimal defaultBalance = client.getBalance("");
        System.out.println("Default account's balance: " + defaultBalance + " BTC");
        // fund seller from default account ""
        client.move("", "seller", defaultBalance);

        // transact
        // step 1) lock up seller's bitcoins in escrow
        client.move("seller", "ESCROW", defaultBalance);
        // step 2) seller releases bitcoins from escrow after buyer pays
        client.move("ESCROW", "buyer", defaultBalance);

        client.listAccounts();

        // reset all accounts
        client.move("buyer", "", defaultBalance);
    }
}