package auction;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import auction.analyzer.Analyzer;
import auction.dao.FilesManager;
import bgbot.Movement;
import farmbot.Pathing.GlobalGraph;
import farmbot.Pathing.Graph;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.Utils;
import winapi.components.WinKey;
import wow.WowInstance;
import wow.memory.CtmManager;
import wow.memory.ObjectManager;
import wow.memory.objects.AuctionManager;
import wow.memory.objects.Player;
import wow.memory.objects.UnitObject;

/**
 * @author alexlovkov
 */
public class Buyer {

    private static final Logger logger = LoggerFactory.getLogger(Buyer.class);
    private static final int MAX_PAGES = 1000;
    private static final int SLEEP1 = 100;
    public static final int SLEEP2 = 1300;
    private static final int MIN_PAGES = 250;

    private static final WowInstance wowInstance = WowInstance.getInstance();
    private final String folder;
    private final FilesManager filesManager;
    private final Analyzer analyzer;
    private final ObjectManager objectManager;
    private final Movement movement;
    private Player player;
    private CtmManager ctmManager;
    private String tmpFile;
    private BufferedWriter historyBufferedWriter;
    private boolean scanOnlyFirstPage;
    private boolean firstIteration;
    private AuctionManager auctionManager;
    private GlobalGraph graph;

    public Buyer(boolean scanOnlyFirstPage, String folder, Analyzer analyzer, FilesManager filesManager) {
        this.folder = folder;
        this.player = wowInstance.getPlayer();
        this.analyzer = analyzer;
        this.ctmManager = wowInstance.getCtmManager();
        this.objectManager = wowInstance.getObjectManager();
        this.tmpFile = folder + File.separator + "tmp" + File.separator + "tmp.txt";
        this.filesManager = filesManager;
        this.scanOnlyFirstPage = scanOnlyFirstPage;
        // TODO: do we really need it? history is big.
        this.firstIteration = false;
        if (player.getZone().isStranglethornVale()) {
            this.graph = new GlobalGraph("routesAuc\\stranglethornVale");
        } else if (player.getZone().isWinterspring()) {
            this.graph = new GlobalGraph("routesAuc\\winterspring");
        }
        this.movement = new Movement(player, ctmManager, wowInstance, objectManager);
        if (graph != null) {
            this.graph.buildGlobalGraph();
        }
    }

    boolean analyze() throws InterruptedException, IOException, ParseException {
        if (scanOnlyFirstPage) {
            return analazeOnlyFirstPage();
        } else {
            return analyzeFullAuction();
        }
    }

    private boolean analyzeFullAuction() throws IOException, ParseException, InterruptedException {
        logger.info("analyzeFullAuction");
        analyzer.calculate();
        resetOnFirstPage();
        this.auctionManager = wowInstance.getAuctionManager();
        Utils.sleep(SLEEP2);
        int pages = scanFullAuction();
        return pages > MIN_PAGES;
    }

    /**
     * scan full auction, and try to buy items if it's not the first scan.
     *
     * @return how many pages were scanned in the auction
     */
    private int scanFullAuction() throws IOException, ParseException, InterruptedException {
        ArrayList<Item[]> itemsFromAuction = new ArrayList<>();
        int page;
        for (page = 1; page <= MAX_PAGES; page++) {
            if (page % 30 == 0 || page == 1) {
                System.out.println("page:" + page);
            }
            Item[] itemsFromCurrentPage = auctionManager.getItemsFromCurrentPageWithRetry();
            if (itemsFromCurrentPage == null) {
                break;
            }
            //Utils.sleep(100);
            Item[] secondRead = auctionManager.getItemsFromCurrentPage();
            if (secondRead == null) {
                break;
            }

            int errorReading = 0;
            if (!firstIteration) {
                for (int i = 0; i < itemsFromCurrentPage.length; i++) {
                    Item item = itemsFromCurrentPage[i];
                    BuyingItem buyingItem = analyzer.buyItem(item, i + 1);
                    if (buyingItem == null) {
                        break;
                    }
                    Item secondReadItem = secondRead[i];
                    if (item.compareFields(secondReadItem)) {
                    } else {
                        errorReading++;
                        logger.error("read another item prev:{} ,second read:{}", item, secondReadItem);
                    }
                    if (buyingItem.getBuyType() != BuyType.NONE) {
                        Utils.sleep(1000);
                        secondReadItem = auctionManager.getItemsFromCurrentPage()[i];
                        if (item.compareFields(secondReadItem) && analyzer.buyItem(secondReadItem, i + 1).getBuyType() != BuyType.NONE) {
                            analyzer.buyItem(item, buyingItem.getBuyType(), buyingItem.getIndex(), page, true);
                            logger.info("found item for buying on page:{}", page);
                        } else {
//                            logger.error("read another item prev:{} ,second read:{}", item, secondReadItem);
                        }
                    }
                }
            }
            if (errorReading > 0) {
                logger.error("for 1 scan we had reading errors:{} page:{}, errorReading:{}", errorReading, page, errorReading);
            }
            itemsFromAuction.add(itemsFromCurrentPage);
            Thread.sleep(SLEEP1);
            auctionManager.nextPage();
            Thread.sleep(SLEEP2);
        }

        if (page < MIN_PAGES) {
            int sleepingTime = 1000 * 60 * 5;
            logger.warn("found not enough pages, something wrong, found only:{}, sleeping for:{}", page, sleepingTime);
            Utils.sleep(sleepingTime);
            resetAuction();
        } else {
            logger.info("found {} pages for the scan", page);
            resetTmpFile();
            for (Item[] itemsFromCurrentPage : itemsFromAuction) {
                writeCurrentAuc(itemsFromCurrentPage);
            }
            firstIteration = false;
            historyBufferedWriter.flush();
            historyBufferedWriter.close();
            filesManager.addToDataBase(folder, tmpFile);
        }
        return page;
    }

    private boolean analazeOnlyFirstPage() throws IOException {
        logger.info("analazeOnlyFirstPage");
        resetOnFirstPage();
        AuctionManager auctionManager = wowInstance.getAuctionManager();
        int count = -1;
        boolean TEST_FLAG = false;
        logger.info("TEST_FLAG:" + TEST_FLAG);
        logger.info("calculate auction");
        analyzer.calculate();
        logger.info("started refreshing auction");
        boolean firstRun = true;
        for (; ; ) {
            count++;
            if (count % 30 == 0) {
                count = 0;
                boolean isDead = wowInstance.getPlayer().isDead();
                objectManager.refillPlayers();
                isDead = isDead | wowInstance.getPlayer().isDead();
                if (isDead) {
                    if (!firstRun) {
                        long sleepBeforeRess = 1000 * 60 * 10;
                        logger.info("sleeping for:{}", sleepBeforeRess);
                        Utils.sleep(sleepBeforeRess);
                    }
                    firstRun = false;
                    logger.info("player is dead, trying to ress");
                    if (player.isDeadLyingDown()) {
                        logger.info("player isDeadLyingDown");
                        for (int i = 0; i < 10; i++) {
                            wowInstance.click(WinKey.D9, 0L);
                            Utils.sleep(100);
                        }
                    }
                    Utils.sleep(5000);
                    if (graph != null) {
                        Point3D point;
                        // auction points for different locations
                        if (player.getZone().isStranglethornVale()) {
                            point = new Point3D(-14417.693, 523.9574, 5.014096);
                        } else if (player.getZone().isWinterspring()) {
                            point = new Point3D(6772.7534, -4679.1396, 723.76514);
                        } else {
                            // TODO: tanaris
                            point = null;
                        }
                        List<Graph.Vertex> shortestPath = graph.getShortestPath(player.getCoordinates(), point);
                        boolean success = true;
                        for (Graph.Vertex vertex : shortestPath) {
                            if (!movement.goToNextPoint(vertex.coordinates)) {
                                success = false;
                                wowInstance.click(WinKey.D0);
                            }
                        }
                        Utils.sleep(1000);
                        ctmManager.goTo(shortestPath.get(shortestPath.size() - 1).getCoordinates(), false);
                        for (int i = 0; i < 120; i++) {
                            wowInstance.click(WinKey.D0, 0L);
                            Utils.sleep(1000);
                            if (!player.isDead()) {
                                break;
                            }
                        }
                        Utils.sleep(2000);
                        wowInstance.click(WinKey.D0);
                        Utils.sleep(2000);
                        // cat form
                        wowInstance.click(WinKey.D5);
                        Utils.sleep(2000);
                        // stealth
                        wowInstance.click(WinKey.D4);
                        resetAuction();
                        //screen shot
                        wowInstance.click(WinKey.P);
                        if (!success) {
                            logger.info("couldn't ress, exit");
                            ctmManager.stop();
                            return false;
                        }
                    }
                }
            }

            Item[] itemsFromCurrentPage = auctionManager.getItemsFromCurrentPage();

            for (int i = 0; i < itemsFromCurrentPage.length; i++) {
                Item item = itemsFromCurrentPage[i];

                BuyingItem buyingItem = analyzer.buyItem(item, i + 1);

                if (buyingItem.getBuyType() == BuyType.BUYOUT) {
                    Item[] secondScan = auctionManager.getItemsFromCurrentPage();
                    logger.info("found item for at index:{} , item:{} ", i, item);
                    if (item.compareFields(secondScan[i])) {
                        analyzer.buyItem(item, buyingItem.getBuyType(), buyingItem.getIndex(), 1, false);
                        // make screenshot of errors or success buying
                        wowInstance.click(WinKey.P);
                    } else {
                        logger.info("items are not equals, item:{} , second item:{}", item, secondScan[i]);
                    }
                    analyzer.buyItem(item, buyingItem.getBuyType(), buyingItem.getIndex(), 1, false);
                }
            }
            wowInstance.click(WinKey.D5);
        }

    }

    void resetOnFirstPage() {
        logger.info("reset auction");
        Utils.sleep(1000);
        wowInstance.click(WinKey.D5);
        Utils.sleep(2000);
        //macros for down list of items, because /nextPage sometimes stuck and doesn't work because of it
        wowInstance.click(WinKey.D2);
        Utils.sleep(2000);
        wowInstance.click(WinKey.D5);
        Utils.sleep(2000);
    }

    void resetAuction() {
        try {
            if (!scanOnlyFirstPage) {
                resetTmpFile();
            }
            logger.info("reset auction");
            //close auctionHouse
            wowInstance.click(WinKey.D1);
            Utils.sleep(6000);
            objectManager.refillUnits();
            Optional<UnitObject> nearestUnitTo = objectManager.getNearestUnitTo(this.player);
            if (nearestUnitTo.isPresent()) {
                UnitObject unitObject = nearestUnitTo.get();
                this.ctmManager.interactNpc(unitObject);
                Utils.sleep(1000L);
                this.ctmManager.interactNpc(unitObject);
                Utils.sleep(100L);
                wowInstance.click(WinKey.W);
                Utils.sleep(100);
                wowInstance.click(WinKey.S, 52);
                Utils.sleep(1000L);
                wowInstance.click(WinKey.D2);
            } else {
                logger.error("auc wasn't found");
            }
            Utils.sleep(1000);
            wowInstance.click(WinKey.D5);
            Utils.sleep(2000);
        } catch (Throwable ignore) {
        }
    }

    private void resetTmpFile() throws IOException {
        historyBufferedWriter = new BufferedWriter(new FileWriter(tmpFile));
        initWrite();
    }


    private boolean equals(Item[] firstScan, Item[] secondScan) {
        for (int i = 0; i < firstScan.length; i++) {
            if (!firstScan[i].compareFields(secondScan[i])) {
                return false;
            }
        }
        return true;
    }


    private boolean validateItems(Item[] itemsFromCurrentPage) {
        if (itemsFromCurrentPage == null) {
            return false;
        }
        for (Item item : itemsFromCurrentPage) {
            // not true when you dont have full page of items
            if (item.getAuctionId() == 0) {
                //return false;
            }
        }
        return true;
    }

    private void initWrite() throws IOException {
        historyBufferedWriter.write(new Date() + "\n");
    }

    private void writeCurrentAuc(Item[] items) throws IOException {
        for (Item item : items) {
            historyBufferedWriter.write(item.toString() + "\n");
        }
    }


    private void printCurrentPage() {
        AuctionManager auctionManager = wowInstance.getAuctionManager();
        Item[] itemsFromCurrentPage = auctionManager.getItemsFromCurrentPageWithRetry();
        if (itemsFromCurrentPage == null) {
            return;
        }
        for (int i = 0; i < itemsFromCurrentPage.length; i++) {
            Item item = itemsFromCurrentPage[i];
            if (item.getExpireTime() == 0) {
                break;
            }
//            if (item.getBuyOut() == 1200 || item.getBuyOut() == 2400 || item.getBuyOut() == 4800) {
            System.out.println("index=" + i + " " + item);
//            }

        }

        for (int i = 0; i < 3; i++) {
            int tickCount = auctionManager.getTickCount();
            long expireTime = itemsFromCurrentPage[i].getExpireTime();
            Calendar expiredCalendar = Utils.getCalendarFromTime(expireTime - tickCount);
            System.out.println(expiredCalendar);
        }

        /*
        for (int i = 0; i < 3; i++) {
            long tickCount = auctionManager.getTickCount();
            System.out.println("getTickCount=" + tickCount);
            long expireTime = itemsFromCurrentPage[i].getExpireTime();
            //expireTime = 1767983218;
            System.out.println("expireTime__=" + expireTime);
            System.out.println(expireTime - tickCount);
        }
        */

    }

    void testLatency() throws IOException, ParseException {
        AuctionManager auctionManager = wowInstance.getAuctionManager();
        analyzer.calculate();
        for (; ; ) {
            System.gc();
            wowInstance.click(WinKey.D4);
            Utils.sleep(1500);
            Item[] secondPage = auctionManager.getItemsFromCurrentPage();
            long start = System.nanoTime();
            wowInstance.click(WinKey.D3); //prev
            int cnt = 0;
            while (equals(secondPage, auctionManager.getItemsFromCurrentPage())) {
                cnt++;
            }
            long finish = System.nanoTime();
            logger.info("phase was: " + (finish - start) + "  cnt:" + cnt + " millis:" + (finish - start) / 1000 / 1000);
            Utils.sleep(1500);
        }
    }
}
