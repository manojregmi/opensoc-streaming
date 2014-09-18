package com.opensoc.topology.runner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.storm.hdfs.bolt.HdfsBolt;
import org.apache.storm.hdfs.bolt.format.DefaultFileNameFormat;
import org.apache.storm.hdfs.bolt.format.DelimitedRecordFormat;
import org.apache.storm.hdfs.bolt.format.FileNameFormat;
import org.apache.storm.hdfs.bolt.format.RecordFormat;
import org.apache.storm.hdfs.bolt.rotation.FileRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy.Units;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.bolt.sync.SyncPolicy;
import org.json.simple.JSONObject;

import storm.kafka.BrokerHosts;
import storm.kafka.KafkaSpout;
import storm.kafka.SpoutConfig;
import storm.kafka.ZkHosts;
import storm.kafka.bolt.KafkaBolt;
import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.spout.RawScheme;
import backtype.storm.spout.SchemeAsMultiScheme;
import backtype.storm.topology.BoltDeclarer;
import backtype.storm.topology.TopologyBuilder;

import com.opensoc.alerts.TelemetryAlertsBolt;
import com.opensoc.alerts.adapters.HbaseWhiteAndBlacklistAdapter;
import com.opensoc.alerts.interfaces.AlertsAdapter;
import com.opensoc.enrichment.adapters.cif.CIFHbaseAdapter;
import com.opensoc.enrichment.adapters.geo.GeoMysqlAdapter;
import com.opensoc.enrichment.adapters.host.HostFromPropertiesFileAdapter;
import com.opensoc.enrichment.adapters.whois.WhoisHBaseAdapter;
import com.opensoc.enrichment.common.GenericEnrichmentBolt;
import com.opensoc.enrichment.interfaces.EnrichmentAdapter;
import com.opensoc.filters.BroMessageFilter;
import com.opensoc.filters.GenericMessageFilter;
import com.opensoc.indexing.TelemetryIndexingBolt;
import com.opensoc.indexing.adapters.ESBaseBulkAdapter;
import com.opensoc.json.serialization.JSONKryoSerializer;
import com.opensoc.parser.interfaces.MessageFilter;
import com.opensoc.parsing.AbstractParserBolt;
import com.opensoc.parsing.TelemetryParserBolt;
import com.opensoc.parsing.parsers.BasicBroParser;
import com.opensoc.test.spouts.GenericInternalTestSpout;
import com.opensoc.topologyhelpers.Cli;
import com.opensoc.topologyhelpers.SettingsLoader;

public abstract class TopologyRunner {

	protected Configuration config;
	protected TopologyBuilder builder;
	protected Config conf;
	protected boolean local_mode = true;
	protected boolean debug = true;
	protected String config_path = null;
	protected String default_config_path = "OpenSOC_Configs";
	protected boolean success = false;
	protected Set<String> activeComponents = new HashSet<String>();
	protected String component = null;

	public void initTopology(String args[], String subdir)
			throws ConfigurationException, AlreadyAliveException,
			InvalidTopologyException {
		Cli command_line = new Cli(args);
		command_line.parse();

		System.out.println("[OpenSOC] Starting topology deployment...");

		debug = command_line.isDebug();
		System.out.println("[OpenSOC] Debug mode set to: " + debug);

		local_mode = command_line.isLocal_mode();
		System.out.println("[OpenSOC] Local mode set to: " + local_mode);

		if (command_line.getPath() != null) {
			config_path = command_line.getPath();
			System.out
					.println("[OpenSOC] Setting config path to external config path: "
							+ config_path);
		} else {
			config_path = default_config_path;
			System.out
					.println("[OpenSOC] Initializing from default internal config path: "
							+ config_path);
		}

		String topology_conf_path = config_path + "/topologies/" + subdir
				+ "/topology.conf";

		String environment_identifier_path = config_path
				+ "/topologies/environment_identifier.conf";
		String topology_identifier_path = config_path + "/topologies/" + subdir
				+ "/topology_identifier.conf";

		System.out.println("[OpenSOC] Looking for environment identifier: "
				+ environment_identifier_path);
		System.out.println("[OpenSOC] Looking for topology identifier: "
				+ topology_identifier_path);
		System.out.println("[OpenSOC] Looking for topology config: "
				+ topology_conf_path);

		config = new PropertiesConfiguration(topology_conf_path);

		JSONObject environment_identifier = SettingsLoader
				.loadEnvironmentIdnetifier(environment_identifier_path);
		JSONObject topology_identifier = SettingsLoader
				.loadTopologyIdnetifier(topology_identifier_path);

		String topology_name = SettingsLoader.generateTopologyName(
				environment_identifier, topology_identifier);

		System.out.println("[OpenSOC] Initializing Topology: " + topology_name);

		builder = new TopologyBuilder();

		conf = new Config();
		conf.registerSerialization(JSONObject.class, JSONKryoSerializer.class);
		conf.setDebug(debug);

		System.out.println("[OpenSOC] Initializing Spout: " + topology_name);

		if (command_line.isGenerator_spout()) {
			String component_name = config.getString("spout.test.name",
					"DefaultTopologySpout");
			success = initializeTestingSpout(component_name);
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"spout.test");
		}

		if (!command_line.isGenerator_spout()) {
			String component_name = config.getString("spout.kafka.name",
					"DefaultTopologyKafkaSpout");
			// activeComponents.add(component_name);
			success = initializeKafkaSpout(component_name);
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"spout.kafka");
		}

		if (config.getBoolean("parser.bolt.enabled", true)) {
			String component_name = config.getString("parser.bolt.name",
					"DefaultTopologyParserBot");
			activeComponents.add(component_name);
			success = initializeParsingBolt(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"parser.bolt");
		}

		if (config.getBoolean("bolt.enrichment.geo.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.geo.name", "DefaultGeoEnrichmentBolt");
			activeComponents.add(component_name);
			success = initializeGeoEnrichment(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.enrichment.geo");
			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"mysql");
		}

		if (config.getBoolean("bolt.enrichment.host.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.host.name", "DefaultHostEnrichmentBolt");
			activeComponents.add(component_name);
			success = initializeHostsEnrichment(topology_name, component_name,
					"OpenSOC_Configs/etc/whitelists/known_hosts.conf");
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.enrichment.host");
		}

		if (config.getBoolean("bolt.enrichment.whois.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.whois.name", "DefaultWhoisEnrichmentBolt");
			activeComponents.add(component_name);
			success = initializeWhoisEnrichment(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.enrichment.whois");
		}

		if (config.getBoolean("bolt.enrichment.cif.enabled", false)) {
			String component_name = config.getString(
					"bolt.enrichment.cif.name", "DefaultCIFEnrichmentBolt");
			activeComponents.add(component_name);
			success = initializeCIFEnrichment(topology_name, component_name);
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.enrichment.cif");
		}

		if (config.getBoolean("bolt.alerts.enabled", false)) {
			String component_name = config.getString("bolt.alerts.name",
					"DefaultAlertsBolt");
			activeComponents.add(component_name);
			success = initializeAlerts(topology_name, component_name,
					config_path + "/topologies/" + subdir + "/alerts.xml",
					environment_identifier, topology_identifier);
			component = component_name;

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.alerts");
		}

		if (config.getBoolean("bolt.kafka.enabled", false)) {
			String component_name = config.getString("bolt.kafka.name",
					"DefaultKafkaBolt");
			// activeComponents.add(component_name);
			success = initializeKafkaBolt(component_name);

			System.out.println("[OpenSOC] Component " + component_name
					+ " initialized");

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.kafka");
		}

		if (config.getBoolean("bolt.indexing.enabled", true)) {
			String component_name = config.getString("bolt.indexing.name",
					"DefaultIndexingBolt");
			activeComponents.add(component_name);
			success = initializeIndexingBolt(component_name);

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.indexing");
		}

		if (config.getBoolean("bolt.hdfs.enabled", false)) {
			String component_name = config.getString("bolt.hdfs.name",
					"DefaultHDFSBolt");
			// activeComponents.add(component_name);
			success = initializeHDFSBolt(topology_name, component_name);

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.hdfs");
		}

		if (config.getBoolean("bolt.error.indexing.enabled")) {
			String component_name = config.getString(
					"bolt.error.indexing.name", "DefaultErrorIndexingBolt");

			success = initializeErrorIndexBolt(component_name);

			System.out.println("[OpenSOC] ------Component " + component_name
					+ " initialized with the following settings:");

			SettingsLoader.printConfigOptions((PropertiesConfiguration) config,
					"bolt.error");
		}

		if (local_mode) {
			conf.setNumWorkers(config.getInt("num.workers"));
			conf.setMaxTaskParallelism(1);
			LocalCluster cluster = new LocalCluster();
			cluster.submitTopology(topology_name, conf,
					builder.createTopology());
		} else {

			conf.setNumWorkers(config.getInt("num.workers"));
			StormSubmitter.submitTopology(topology_name, conf,
					builder.createTopology());
		}

	}

	private boolean initializeErrorIndexBolt(String component_name) {
		try {

			TelemetryIndexingBolt indexing_bolt = new TelemetryIndexingBolt()
					.withIndexIP(config.getString("es.ip"))
					.withIndexPort(config.getInt("es.port"))
					.withClusterName(config.getString("es.clustername"))
					.withIndexName(
							config.getString("bolt.error.indexing.indexname"))
					.withDocumentName(
							config.getString("bolt.error.indexing.documentname"))
					.withBulk(config.getInt("bolt.error.indexing.bulk"))
					.withIndexAdapter(new ESBaseBulkAdapter())
					.withMetricConfiguration(config);

			BoltDeclarer declarer = builder
					.setBolt(
							component_name,
							indexing_bolt,
							config.getInt("bolt.error.indexing.parallelism.hint"))
					.setNumTasks(config.getInt("bolt.error.indexing.num.tasks"));

			for (String component : activeComponents)
				declarer.shuffleGrouping(component, "error");

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

	}

	private boolean initializeKafkaSpout(String name) {
		try {

			BrokerHosts zk = new ZkHosts(config.getString("kafka.zk"));
			String input_topic = config.getString("spout.kafka.topic");
			SpoutConfig kafkaConfig = new SpoutConfig(zk, input_topic, "",
					input_topic);
			kafkaConfig.scheme = new SchemeAsMultiScheme(new RawScheme());
			// kafkaConfig.forceFromStart = Boolean.valueOf("True");
			kafkaConfig.startOffsetTime = -1;

			builder.setSpout(name, new KafkaSpout(kafkaConfig),
					config.getInt("spout.kafka.parallelism.hint")).setNumTasks(
					config.getInt("spout.kafka.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	abstract boolean initializeParsingBolt(String topology_name, String name);

	abstract boolean initializeTestingSpout(String name);

	private boolean initializeGeoEnrichment(String topology_name, String name) {

		try {
			List<String> geo_keys = new ArrayList<String>();
			geo_keys.add(config.getString("source.ip"));
			geo_keys.add(config.getString("dest.ip"));

			GeoMysqlAdapter geo_adapter = new GeoMysqlAdapter(
					config.getString("mysql.ip"), config.getInt("mysql.port"),
					config.getString("mysql.username"),
					config.getString("mysql.password"),
					config.getString("bolt.enrichment.geo.adapter.table"));

			GenericEnrichmentBolt geo_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.geo.enrichment_tag"))
					.withOutputFieldName(topology_name)
					.withAdapter(geo_adapter)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.geo.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.geo.MAX_CACHE_SIZE"))
					.withKeys(geo_keys).withMetricConfiguration(config);

			builder.setBolt(name, geo_enrichment,
					config.getInt("bolt.enrichment.geo.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.enrichment.geo.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	private boolean initializeHostsEnrichment(String topology_name,
			String name, String hosts_path) {

		try {
			List<String> hosts_keys = new ArrayList<String>();
			hosts_keys.add(config.getString("source.ip"));
			hosts_keys.add(config.getString("dest.ip"));

			Map<String, JSONObject> known_hosts = SettingsLoader
					.loadKnownHosts(hosts_path);

			HostFromPropertiesFileAdapter host_adapter = new HostFromPropertiesFileAdapter(
					known_hosts);

			GenericEnrichmentBolt host_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.host.enrichment_tag"))
					.withAdapter(host_adapter)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.host.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.host.MAX_CACHE_SIZE"))
					.withOutputFieldName(topology_name).withKeys(hosts_keys)
					.withMetricConfiguration(config);

			builder.setBolt(name, host_enrichment,
					config.getInt("bolt.enrichment.host.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(
							config.getInt("bolt.enrichment.host.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	private boolean initializeAlerts(String topology_name, String name,
			String alerts_path, JSONObject environment_identifier,
			JSONObject topology_identifier) {
		try {

			JSONObject alerts_identifier = SettingsLoader
					.generateAlertsIdentifier(environment_identifier,
							topology_identifier);

			AlertsAdapter alerts_adapter = new HbaseWhiteAndBlacklistAdapter(
					"ip_whitelist", "ip_blacklist",
					config.getString("kafka.zk.list"),
					config.getString("kafka.zk.port"), 3600, 1000);

			TelemetryAlertsBolt alerts_bolt = new TelemetryAlertsBolt()
					.withIdentifier(alerts_identifier).withMaxCacheSize(1000)
					.withMaxTimeRetain(3600).withAlertsAdapter(alerts_adapter)
					.withOutputFieldName("message")
					.withMetricConfiguration(config);

			builder.setBolt(name, alerts_bolt,
					config.getInt("bolt.alerts.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.alerts.num.tasks"));

			TelemetryIndexingBolt indexing_bolt = new TelemetryIndexingBolt()
					.withIndexIP(config.getString("es.ip"))
					.withIndexPort(config.getInt("es.port"))
					.withClusterName(config.getString("es.clustername"))
					.withIndexName(
							config.getString("bolt.alerts.indexing.indexname"))
					.withDocumentName(
							config.getString("bolt.alerts.indexing.documentname"))
					.withBulk(config.getInt("bolt.alerts.indexing.bulk"))
					.withIndexAdapter(new ESBaseBulkAdapter())
					.withMetricConfiguration(config);

			if (config.getBoolean("bolt.alerts.indexing.enabled")) {

				String alerts_name = config
						.getString("bolt.alerts.indexing.name");
				builder.setBolt(alerts_name, indexing_bolt,
						config.getInt("bolt.indexing.parallelism.hint"))
						.shuffleGrouping(name, "alert")
						.setNumTasks(config.getInt("bolt.indexing.num.tasks"));

				activeComponents.add(alerts_name);
			}

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
		return true;
	}

	private boolean initializeKafkaBolt(String name) {
		try {

			Map<String, String> kafka_broker_properties = new HashMap<String, String>();
			kafka_broker_properties.put("zk.connect",
					config.getString("kafka.zk"));
			kafka_broker_properties.put("metadata.broker.list",
					config.getString("kafka.br"));

			kafka_broker_properties.put("serializer.class",
					"com.opensoc.json.serialization.JSONKafkaSerializer");

			String output_topic = config.getString("bolt.kafka.topic");

			conf.put("kafka.broker.properties", kafka_broker_properties);
			conf.put("topic", output_topic);

			builder.setBolt(name, new KafkaBolt<String, String>(),
					config.getInt("bolt.kafka.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.kafka.num.tasks"));
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
		return true;
	}

	private boolean initializeWhoisEnrichment(String topology_name, String name) {
		try {

			List<String> whois_keys = new ArrayList<String>();
			String[] keys_from_settings = config.getString(
					"bolt.enrichment.whois.source").split(",");

			for (String key : keys_from_settings)
				whois_keys.add(key);

			EnrichmentAdapter whois_adapter = new WhoisHBaseAdapter(
					config.getString("bolt.enrichment.whois.hbase.table.name"),
					config.getString("kafka.zk.list"),
					config.getString("kafka.zk.port"));

			GenericEnrichmentBolt whois_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.whois.enrichment_tag"))
					.withOutputFieldName(topology_name)
					.withAdapter(whois_adapter)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.whois.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.whois.MAX_CACHE_SIZE"))
					.withKeys(whois_keys).withMetricConfiguration(config);

			builder.setBolt(name, whois_enrichment,
					config.getInt("bolt.enrichment.whois.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(
							config.getInt("bolt.enrichment.whois.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	private boolean initializeIndexingBolt(String name) {
		try {

			TelemetryIndexingBolt indexing_bolt = new TelemetryIndexingBolt()
					.withIndexIP(config.getString("es.ip"))
					.withIndexPort(config.getInt("es.port"))
					.withClusterName(config.getString("es.clustername"))
					.withIndexName(config.getString("bolt.indexing.indexname"))
					.withDocumentName(
							config.getString("bolt.indexing.documentname"))
					.withBulk(config.getInt("bolt.indexing.bulk"))
					.withIndexAdapter(new ESBaseBulkAdapter())
					.withMetricConfiguration(config);

			builder.setBolt(name, indexing_bolt,
					config.getInt("bolt.indexing.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.indexing.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	private boolean initializeCIFEnrichment(String topology_name, String name) {
		try {

			List<String> cif_keys = new ArrayList<String>();

			cif_keys.add(config.getString("source.ip"));
			cif_keys.add(config.getString("dest.ip"));
			cif_keys.add(config.getString("bolt.enrichment.cif.host"));
			cif_keys.add(config.getString("bolt.enrichment.cif.email"));

			GenericEnrichmentBolt cif_enrichment = new GenericEnrichmentBolt()
					.withEnrichmentTag(
							config.getString("bolt.enrichment.cif.enrichment_tag"))
					.withAdapter(
							new CIFHbaseAdapter(config
									.getString("kafka.zk.list"), config
									.getString("kafka.zk.port"), config
									.getString("bolt.enrichment.cif.tablename")))
					.withOutputFieldName(topology_name)
					.withEnrichmentTag("CIF_Enrichment")
					.withKeys(cif_keys)
					.withMaxTimeRetain(
							config.getInt("bolt.enrichment.cif.MAX_TIME_RETAIN"))
					.withMaxCacheSize(
							config.getInt("bolt.enrichment.cif.MAX_CACHE_SIZE"))
					.withMetricConfiguration(config);

			builder.setBolt(name, cif_enrichment,
					config.getInt("bolt.enrichment.cif.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.enrichment.cif.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	private boolean initializeHDFSBolt(String topology_name, String name) {
		try {

			// * ------------HDFS BOLT configuration

			/*
			 * FileNameFormat fileNameFormat = new DefaultFileNameFormat()
			 * .withPath("/" + topology_name + "/"); RecordFormat format = new
			 * DelimitedRecordFormat() .withFieldDelimiter("|");
			 * 
			 * SyncPolicy syncPolicy = new CountSyncPolicy(5);
			 * FileRotationPolicy rotationPolicy = new
			 * FileSizeRotationPolicy(config
			 * .getFloat("bolt.hdfs.size.rotation.policy" ), Units.KB);
			 * 
			 * HdfsBolt hdfsBolt = new
			 * HdfsBolt().withFsUrl(config.getString("bolt.hdfs.fs.url"))
			 * .withFileNameFormat(fileNameFormat).withRecordFormat(format)
			 * .withRotationPolicy(rotationPolicy).withSyncPolicy(syncPolicy);
			 * 
			 * builder.setBolt("HDFSBolt", hdfsBolt,
			 * config.getInt("bolt.hdfs.parallelism.hint"))
			 * .shuffleGrouping("CIFEnrichmentBolt"
			 * ).setNumTasks(config.getInt("bolt.hdfs.num.tasks"));
			 */

			// * ------------HDFS BOLT For Enriched Data configuration

			FileNameFormat fileNameFormat_enriched = new DefaultFileNameFormat()
					.withPath(config.getString("bolt.hdfs.path", "/") + "/"
							+ topology_name + "_enriched/");
			RecordFormat format_enriched = new DelimitedRecordFormat()
					.withFieldDelimiter("|");

			SyncPolicy syncPolicy_enriched = new CountSyncPolicy(5);
			FileRotationPolicy rotationPolicy_enriched = new FileSizeRotationPolicy(
					config.getFloat("bolt.hdfs.size.rotation.policy"), Units.KB);

			HdfsBolt hdfsBolt_enriched = new HdfsBolt()
					.withFsUrl(config.getString("bolt.hdfs.fs.url"))
					.withFileNameFormat(fileNameFormat_enriched)
					.withRecordFormat(format_enriched)
					.withRotationPolicy(rotationPolicy_enriched)
					.withSyncPolicy(syncPolicy_enriched);

			builder.setBolt(name, hdfsBolt_enriched,
					config.getInt("bolt.hdfs.parallelism.hint"))
					.shuffleGrouping(component)
					.setNumTasks(config.getInt("bolt.hdfs.num.tasks"));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}
}
