
import com.google.common.collect.Lists;
import data.TestDataSet;
import evaluate.AUC;
import model.DNN;
import model.Model;
import net.PServer;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import context.Context;
import train.Trainer;
import update.AdamUpdater;
import update.Updater;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class Main {

	private static Logger logger = LoggerFactory.getLogger(Main.class);


	public static void main(String args[]) throws Exception {
		Context.init();
		if (Context.isPServer()) {
			// 启动PS进程
			Updater updater = new AdamUpdater(0.001, 0.9, 0.999, Math.pow(10, -8));
			PServer server = new PServer(Context.psPort, Context.workerNum);
			server.getUpdaterMap().put(updater.getName(), updater);
			server.start();
			System.exit(0);
		}
		BufferedReader train = new BufferedReader(new FileReader(new File(System.getProperty("context", "/Users/mengjun/dev/ps/src/main/resources/train.txt"))));
		BufferedReader test = new BufferedReader(new FileReader(new File(System.getProperty("test", "/Users/mengjun/dev/ps/src/main/resources/test.txt"))));
		Trainer trainer = new Trainer(Context.thread, new Callable<Model>() {
			@Override
			public Model call() throws Exception {
				return DNN.buildModel(23, 10, 45, new int[]{1000, 100, 1});
			}
		});
		for (int epoch = 0; epoch < 100 && !Context.finish; epoch++) {
			logger.info("epoch {}", epoch);
			int n = 0;
			boolean eof = false;
			while (!Context.finish && !eof) {
				List<TestDataSet.MatrixData> dataList = Lists.newArrayList();
				for (int i=0; i<Context.thread; i++) {
					Pair<TestDataSet.MatrixData, Boolean> d = TestDataSet.fromStream(train, Integer.parseInt(System.getProperty("batch", "5000")));
					if (!d.getValue()) {
						logger.info("data read eof");
						eof = true;
						break;
					}
					dataList.add(d.getKey());
				}
				trainer.run(dataList);
				if (n ++ == 100) {
					Context.dump = true;
					n = 0;
				}
			}
			logger.info("begin compute auc...");
			List<Pair<Double, Double>> data = new ArrayList<Pair<Double, Double>>();
			while (true) {
				Pair<TestDataSet.MatrixData, Boolean> tData = TestDataSet.fromStream(test, Integer.parseInt(System.getProperty("batch", "5000")));
				if (!tData.getValue()) {
					break;
				}
				float[] y = tData.getKey().getY().toArray();
				float[] p = trainer.getTrainResult().predict(tData.getKey().getE(), tData.getKey().getX()).toArray();
				for (int i=0; i<y.length; i++) {
					data.add(new MutablePair<Double, Double>((double) p[i], (double) y[i]));
				}
			}
			AUC auc = new AUC(data);
			logger.info("AUC {}", auc.calculate());
			train = new BufferedReader(new FileReader(new File(System.getProperty("context", "/Users/mengjun/dev/ps/src/main/resources/train.txt"))));
			test = new BufferedReader(new FileReader(new File(System.getProperty("test", "/Users/mengjun/dev/ps/src/main/resources/test.txt"))));
		}
	}
}