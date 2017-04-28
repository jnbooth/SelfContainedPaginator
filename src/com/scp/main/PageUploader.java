package com.scp.main;

import java.net.URL;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.apache.xmlrpc.client.XmlRpcSun15HttpTransportFactory;
import org.jsoup.Jsoup;
import org.postgresql.util.PSQLException;

import com.scp.connection.CloseableStatement;
import com.scp.connection.Configs;
import com.scp.connection.Connector;
import com.scp.connection.Queries;

public class PageUploader {
	private static final Logger logger = Logger.getLogger(PageUploader.class);
	private static XmlRpcClientConfigImpl config;
	private static XmlRpcClient client;
	private static ArrayList<Page> pages;

	static {
		config = new XmlRpcClientConfigImpl();
		try {
			config.setServerURL(new URL(Configs.getSingleProperty(
					"wikidotServer").getValue()));
			config.setBasicUserName(Configs.getSingleProperty("appName")
					.getValue());
			config.setBasicPassword(Configs.getSingleProperty("wikidotapikey")
					.getValue());
			config.setEnabledForExceptions(true);
			config.setConnectionTimeout(10 * 1000);
			config.setReplyTimeout(30 * 1000);

			client = new XmlRpcClient();
			client.setTransportFactory(new XmlRpcSun15HttpTransportFactory(
					client));
			client.setTypeFactory(new XmlRpcTypeNil(client));
			client.setConfig(config);

		} catch (Exception e) {
			logger.error("There was an exception", e);
		}

	}

	private static void loadPages() {
		Tags.reloadTags();
		pages = new ArrayList<Page>();
		try {
			CloseableStatement stmt = Connector.getStatement(Queries
					.getQuery("getStoredPages"));
			ResultSet rs = stmt.getResultSet();
			logger.info("Beginning load of Stored Pages");
			while (rs != null && rs.next()) {

				try {
					pages.add(new Page(rs.getString("pagename") == null ? ""
							: rs.getString("pagename"),
							rs.getString("title") == null ? "" : rs
									.getString("pagename"),
							rs.getInt("rating"),
							rs.getString("created_by") == null ? "" : rs
									.getString("created_by"), rs
									.getTimestamp("created_on"), rs
									.getBoolean("scpPage"), rs
									.getString("scpTitle") == null ? "" : rs
									.getString("title"), Tags.getTags(rs
									.getString("pagename"))));
				} catch (PSQLException e) {
					logger.error("Couldn't create page, keep going", e);
				}

			}
			rs.close();
			stmt.close();
		} catch (Exception e) {
			logger.error("There was an exception retreiving stored pages", e);
		}
	}

	public static void main(String[] args) {
		try {
			loadPages();
			listPage();
			gatherMetadata();
			uploadSeries();

		} catch (Exception e) {
			logger.error("Error checking if update required.", e);
		}
	}

	public static void uploadSeries() {
		String regex = "(?m)<li><a href=\"\\/(.+)\">(.+)<\\/a> - (.+)<\\/li>";
		Pattern r = Pattern.compile(regex);
		logger.info("Beggining gather of series pages: 1, 2, 3, 4 and jokes");
		String[] series = new String[] { "scp-series	", "scp-series-2",
				"scp-series-3", "scp-series-4", "joke-scps" };

		for (String page : series) {
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("site", "scp-wiki");
			params.put("page", page);

			try {
				@SuppressWarnings("unchecked")
				HashMap<String, Object> result = (HashMap<String, Object>) pushToAPI(
						"pages.get_one", params);

				String[] lines = ((String) result.get("html")).split("\n");
				ArrayList<String[]> pagelist = new ArrayList<String[]>();
				for (String s : lines) {
					Matcher m = r.matcher(s);
					if (m.find()) {
						pagelist.add(new String[] { m.group(1), m.group(2),
								Jsoup.parse(m.group(3)).text() });
					}
				}

				ArrayList<String[]> insertPages = new ArrayList<String[]>();
				ArrayList<String[]> updateList = new ArrayList<String[]>();

				for (String[] pageParts : pagelist) {
					boolean found = false;
					for (Page pageOb : pages) {
						if (pageOb.getPageLink().equalsIgnoreCase(pageParts[0])) {
							found = true;
							if (!pageOb.getScpTitle().equalsIgnoreCase(
									pageParts[1])) {
								updateList.add(pageParts);
								pageOb.setScpTitle(pageParts[0]);
								pageOb.setScpPage(true);
							}
						}
					}
					if (!found) {
						// This should never actually happen. The site scrape
						// should handle this.
						insertPages.add(pageParts);
					}
				}

				for (String[] insert : insertPages) {
					CloseableStatement stmt = Connector.getStatement(
							Queries.getQuery("insertPage"), insert[0],
							insert[2]);
					stmt.executeUpdate();
				}
				for (String[] update : updateList) {
					CloseableStatement stmt = Connector.getStatement(
							Queries.getQuery("updateTitle"), update[2],
							update[0]);
					stmt.executeUpdate();
				}

			} catch (Exception e) {
				if (!e.getMessage().contains("unique")) {
					logger.error(
							"There was an exception attempting to grab the series page metadata",
							e);
				}
			}
		}
		logger.info("Finished gathering series pages");
	}

	private static void gatherMetadata() {
		try {
			logger.info("Gathering metadata.");
			int j = 0;
			Page[] pageSet = new Page[10];
			for (Page str : pages) {
				if (j < 10) {
					pageSet[j] = str;
					j++;
				} else {
					getPageInfo(pageSet);
					pageSet = new Page[10];
					j = 0;
				}
			}
			logger.info("Finished gathering metadata");
		} catch (Exception e) {
			logger.error(
					"There was an error attempting to get pages in groups of ten",
					e);
		}
	}

	private static Object pushToAPI(String method, Object... params)
			throws XmlRpcException {
		return (Object) client.execute(method, params);
	}

	public static void listPage() {
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("site", Configs.getSingleProperty("site").getValue());
		try {
			logger.info("Beginning site-wide page gather");
			Object[] result = (Object[]) pushToAPI("pages.select", params);
			// Convert result to a String[]
			String[] pageList = new String[result.length];
			for (int i = 0; i < result.length; i++) {
				pageList[i] = (String) result[i];
			}
			logger.info(pageList.length);
			for (String str : pageList) {
				try {
					CloseableStatement stmt = Connector.getStatement(
							Queries.getQuery("insertPage"), str, str);
					stmt.executeUpdate();
				} catch (Exception e) {
					if (!e.getMessage().contains("unique")) {
						logger.error("Couldn't insert page name", e);
					}
				}
			}
			logger.info("Ending site-wide page gather");
		} catch (Exception e) {
			logger.error("There was an exception", e);
		}
	}

	public static String getPageInfo(Page[] pages) {
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("site", Configs.getSingleProperty("site").getValue());
		String[] pageNames = new String[10];
		for (int i = 0; i < pages.length; i++) {
			pageNames[i] = pages[i].getPageLink();
		}
		params.put("pages", pageNames);
		ArrayList<String> keyswewant = new ArrayList<String>();
		keyswewant.add("title_shown");
		keyswewant.add("rating");
		keyswewant.add("created_at");
		keyswewant.add("title");
		keyswewant.add("created_by");
		keyswewant.add("tags");
		try {
			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			@SuppressWarnings("unchecked")
			HashMap<String, HashMap<String, Object>> result = (HashMap<String, HashMap<String, Object>>) pushToAPI(
					"pages.get_meta", params);

			StringBuilder returnString = new StringBuilder();
			
			for (String targetName : result.keySet()) {
				Page p = null;
				for (Page page : pages) {
					if (targetName.equals(page.getPageLink())) {
						p = page;
					}
				}
				try {
					// String title = (String)
					// result.get(targetName).get("title");
					String displayTitle = (String) result.get(targetName).get(
							"title_shown");
					Integer rating = (Integer) result.get(targetName).get(
							"rating");
					String creator = (String) result.get(targetName).get(
							"created_by");
					Date createdAt = df.parse((String) result.get(targetName)
							.get("created_at"));
					// For each page, if the tags don't match the database tags,
					Object[] tags = (Object[]) result.get(targetName).get(
							"tags");

					ArrayList<Object> insertTags = new ArrayList<Object>();
					ArrayList<Tag> deleteTags = new ArrayList<Tag>();
					ArrayList<Tag> dbTags = Tags.getTags(targetName);

					for (Object obj : tags) {
						if (!dbTags.contains(obj.toString())) {
							insertTags.add(obj);
						}
					}

					for (Tag tag : dbTags) {
						boolean keep = false;
						for (int i = 0; i < tags.length; i++) {
							if (tags[i].toString()
									.equalsIgnoreCase(tag.tagName)) {
								keep = true;
							}
						}
						if (!keep) {
							deleteTags.add(tag);
						}
					}

					for (Object obj : insertTags) {
						try {
							CloseableStatement stmt = Connector.getStatement(
									Queries.getQuery("insertPageTag"),
									targetName, obj.toString());
							stmt.executeUpdate();
						} catch (PSQLException e) {
							if (!e.getMessage().contains("unique")) {
								logger.error(
										"There was a problem inserting tags", e);
							}
						}
					}

					for (Object obj : deleteTags) {
						CloseableStatement stmt = Connector.getStatement(
								Queries.getQuery("deletePageTag"), targetName,
								obj.toString());
						stmt.executeUpdate();
					}

					CloseableStatement stmt = Connector
							.getStatement(
									Queries.getQuery("updateMetadata"),
									displayTitle == null ? "unknown"
											: displayTitle,
									rating == null ? 0 : rating,
									creator == null ? "unknown" : creator,
									new java.sql.Timestamp(
											createdAt == null ? System
													.currentTimeMillis()
													: createdAt.getTime()),
									targetName);

					stmt.executeUpdate();
				} catch (Exception e) {
					logger.error("Error updating metadata", e);
				}
			}
		} catch (Exception e) {
			logger.error("There was an exception retreiving metadata", e);
		}

		return "I couldn't find anything matching that, apologies.";
	}
}
