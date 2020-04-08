package cn.itcast.tags.etl.mr;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 定义常量
 */
interface Constants {
	// hive表数据目录
	String INPUT_PATH = "hdfs://bigdata-cdh01.itcast.cn:8020/user/hive/warehouse/tags_dat.db/tbl_logs";
	// 生成的hfile目录
	String HFILE_PATH = "hdfs://bigdata-cdh01.itcast.cn:8020/datas/output_hfile/tbl_logs";
	// 表名
	String TABLE_NAME = "tbl_logs";
	// 列簇名称
	byte[] COLUMN_FAMILY = Bytes.toBytes("detail");
	// 表字段
	List<byte[]> list = new ArrayList<byte[]>() {
		private static final long serialVersionUID = -6125158551837044300L;
		{ //
			add(Bytes.toBytes("id"));
			add(Bytes.toBytes("log_id"));
			add(Bytes.toBytes("remote_ip"));
			add(Bytes.toBytes("site_global_ticket"));
			add(Bytes.toBytes("site_global_session"));
			add(Bytes.toBytes("global_user_id"));
			add(Bytes.toBytes("cookie_text"));
			add(Bytes.toBytes("user_agent"));
			add(Bytes.toBytes("ref_url"));
			add(Bytes.toBytes("loc_url"));
			add(Bytes.toBytes("log_time"));
		} //
	};

}


/**
 * 将Hive表数据转换为HFile文件并移动HFile到HBase
 */
public class LoadLogsToHBaseMapReduce extends Configured implements Tool {

	// 连接HBase Connection对象
	private static Connection connection = null;

	/**
	 * 定义Mapper类，读取TSV格式数据，转换为Put对象，存储HBase表
	 */
	static class LoadLogsToHBase extends Mapper<LongWritable, Text, ImmutableBytesWritable, Put> {

		@Override
		protected void map(LongWritable key, Text value, Context context)
				throws IOException, InterruptedException {
			// 按照分隔符分割数据，分隔符为 逗号
			String[] split = value.toString().split("\\t", 11);

			context.getCounter("Group-Counters", "Validte-Counters").increment(1L);
			if (split.length == Constants.list.size()) {
				// 构建Put对象，将每行数据转换为Put
				Put put = new Put(Bytes.toBytes(split[0]));
				for (int i = 1; i < Constants.list.size(); i++) {
					put.addColumn(//
							Constants.COLUMN_FAMILY, //
							Constants.list.get(i), //
							Bytes.toBytes(split[i]) //
					);
				}

				// 将数据输出
				context.write(new ImmutableBytesWritable(put.getRow()), put);
			}
		}

	}


	@Override
	public int run(String[] args) throws Exception {
		// a. 获取配置信息对象
		Configuration configuration = super.getConf();

		// b. 构建Job对象Job
		Job job = Job.getInstance(configuration);
		job.setJobName(this.getClass().getSimpleName());
		job.setJarByClass(LoadLogsToHBaseMapReduce.class);

		// c. 设置Job
		FileInputFormat.addInputPath(job, new Path(Constants.INPUT_PATH));

		job.setMapperClass(LoadLogsToHBase.class);
		job.setMapOutputKeyClass(ImmutableBytesWritable.class);
		job.setMapOutputValueClass(Put.class);

		// TODO: 核心，如何将数据输出到HFile文件中，使用HFileOutputFormat2输出格式完成
		job.setOutputFormatClass(HFileOutputFormat2.class);

		// 判断输出目录是否存在，如果存在就删除
		FileSystem hdfs = FileSystem.get(configuration);
		Path outputPath = new Path(Constants.HFILE_PATH);
		if (hdfs.exists(outputPath)) {
			hdfs.delete(outputPath, true);
		}
		FileOutputFormat.setOutputPath(job, outputPath);

		// 获取HBase Table
		Table table = connection.getTable(TableName.valueOf(Constants.TABLE_NAME));
		HFileOutputFormat2.configureIncrementalLoad( //
				job, //
				table, //
				connection.getRegionLocator(TableName.valueOf(Constants.TABLE_NAME)) //
		);

		// 提交运行Job，返回是否执行成功
		boolean isSuccess = job.waitForCompletion(true);
		return isSuccess ? 0 : 1;
	}


	public static void main(String[] args) throws Exception {
		// 获取Configuration对象，读取配置信息
		Configuration configuration = HBaseConfiguration.create();
		// 获取HBase 连接Connection对象
		connection = ConnectionFactory.createConnection(configuration);

		// 运行MapReduce将数据文件转换为HFile文件
		int status = ToolRunner.run(configuration, new LoadLogsToHBaseMapReduce(), args);
		System.out.println("HFile文件生成完毕!~~~");

		// 运行成功时，加载HFile文件数据到HBase表中
		if (0 == status) {
			// 获取HBase Table句柄
			Admin admin = connection.getAdmin();
			Table table = connection.getTable(TableName.valueOf(Constants.TABLE_NAME));
			// 加载数据到表中
			LoadIncrementalHFiles load = new LoadIncrementalHFiles(configuration);
			load.doBulkLoad(
					new Path(Constants.HFILE_PATH), //
					admin, //
					table, //
					connection.getRegionLocator(TableName.valueOf(Constants.TABLE_NAME)) //
			);
			System.out.println("HFile文件移动完毕!~~~");
		}
	}

}