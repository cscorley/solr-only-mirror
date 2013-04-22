package org.apache.solr.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.xpath.XPathExpressionException;

import org.apache.commons.lang.StringUtils;
import org.apache.solr.cloud.CloudDescriptor;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.core.SolrXMLSerializer.SolrCoreXMLDef;
import org.apache.solr.update.SolrCoreState;
import org.apache.solr.util.DOMUtil;
import org.apache.zookeeper.KeeperException;
import org.w3c.dom.Node;


class CoreMaps {
  private static SolrXMLSerializer SOLR_XML_SERIALIZER = new SolrXMLSerializer();
  private static Object locker = new Object(); // for locking around manipulating any of the core maps.
  private final Map<String, SolrCore> cores = new LinkedHashMap<String, SolrCore>(); // For "permanent" cores

  //WARNING! The _only_ place you put anything into the list of transient cores is with the putTransientCore method!
  private Map<String, SolrCore> transientCores = new LinkedHashMap<String, SolrCore>(); // For "lazily loaded" cores

  private final Map<String, CoreDescriptor> dynamicDescriptors = new LinkedHashMap<String, CoreDescriptor>();

  private final Map<String, SolrCore> createdCores = new LinkedHashMap<String, SolrCore>();

  private Map<SolrCore, String> coreToOrigName = new ConcurrentHashMap<SolrCore, String>();

  private final CoreContainer container;

  // This map will hold objects that are being currently operated on. The core (value) may be null in the case of
  // initial load. The rule is, never to any operation on a core that is currently being operated upon.
  private static final Set<String> pendingCoreOps = new HashSet<String>();

  // Due to the fact that closes happen potentially whenever anything is _added_ to the transient core list, we need
  // to essentially queue them up to be handled via pendingCoreOps.
  private static final List<SolrCore> pendingCloses = new ArrayList<SolrCore>();

  CoreMaps(CoreContainer container) {
    this.container = container;
  }

  // Trivial helper method for load, note it implements LRU on transient cores. Also note, if
  // there is no setting for max size, nothing is done and all cores go in the regular "cores" list
  protected void allocateLazyCores(final ConfigSolr cfg, final SolrResourceLoader loader) {
    final int transientCacheSize = cfg.getInt(ConfigSolr.CfgProp.SOLR_TRANSIENTCACHESIZE, Integer.MAX_VALUE);
    if (transientCacheSize != Integer.MAX_VALUE) {
      CoreContainer.log.info("Allocating transient cache for {} transient cores", transientCacheSize);
      transientCores = new LinkedHashMap<String, SolrCore>(transientCacheSize, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SolrCore> eldest) {
          if (size() > transientCacheSize) {
            synchronized (locker) {
              pendingCloses.add(eldest.getValue()); // Essentially just queue this core up for closing.
              locker.notifyAll(); // Wakes up closer thread too
            }
            return true;
          }
          return false;
        }
      };
    }
  }

  protected void putDynamicDescriptor(String rawName, CoreDescriptor p) {
    synchronized (locker) {
      dynamicDescriptors.put(rawName, p);
    }
  }

  // We are shutting down. You can't hold the lock on the various lists of cores while they shut down, so we need to
  // make a temporary copy of the names and shut them down outside the lock.
  protected void clearMaps(ConfigSolr cfg) {
    List<String> coreNames;
    List<String> transientNames;
    List<SolrCore> pendingToClose;

    // It might be possible for one of the cores to move from one list to another while we're closing them. So
    // loop through the lists until they're all empty. In particular, the core could have moved from the transient
    // list to the pendingCloses list.

    while (true) {
      synchronized (locker) {
        coreNames = new ArrayList<String>(cores.keySet());
        transientNames = new ArrayList<String>(transientCores.keySet());
        pendingToClose = new ArrayList<SolrCore>(pendingCloses);
      }

      if (coreNames.size() == 0 && transientNames.size() == 0 && pendingToClose.size() == 0) break;

      for (String coreName : coreNames) {
        SolrCore core = cores.get(coreName);
        if (core == null) {
          CoreContainer.log.info("Core " + coreName + " moved from core container list before closing.");
        } else {
          try {
            // nocommit: wtf is this?
           // addPersistOneCore(cfg, container.loader, core.getCoreDescriptor(), getCoreToOrigName(core));

            core.close();
          } catch (Throwable t) {
            SolrException.log(CoreContainer.log, "Error shutting down core", t);
          } finally {
            synchronized (locker) {
              cores.remove(coreName);
            }
          }
        }
      }

      for (String coreName : transientNames) {
        SolrCore core = transientCores.get(coreName);
        if (core == null) {
          CoreContainer.log.info("Core " + coreName + " moved from transient core container list before closing.");
        } else {
          try {
            core.close();
          } catch (Throwable t) {
            SolrException.log(CoreContainer.log, "Error shutting down core", t);
          } finally {
            synchronized (locker) {
              transientCores.remove(coreName);
            }
          }
        }
      }

      // We might have some cores that we were _thinking_ about shutting down, so take care of those too.
      for (SolrCore core : pendingToClose) {
        try {
          core.close();
        } catch (Throwable t) {
          SolrException.log(CoreContainer.log, "Error shutting down core", t);
        } finally {
          synchronized (locker) {
            pendingCloses.remove(core);
          }
        }
      }
    }
  }

  protected void addCoresToList(ArrayList<SolrCoreState> coreStates) {
    List<SolrCore> addCores;
    synchronized (locker) {
      addCores = new ArrayList<SolrCore>(cores.values());
    }
    for (SolrCore core : addCores) {
      coreStates.add(core.getUpdateHandler().getSolrCoreState());
    }
  }

  //WARNING! This should be the _only_ place you put anything into the list of transient cores!
  protected SolrCore putTransientCore(ConfigSolr cfg, String name, SolrCore core, SolrResourceLoader loader) {
    SolrCore retCore;
    CoreContainer.log.info("Opening transient core {}", name);
    synchronized (locker) {
      retCore = transientCores.put(name, core);
    }
    return retCore;
  }

  protected SolrCore putCore(String name, SolrCore core) {
    synchronized (locker) {
      return cores.put(name, core);
    }
  }

  List<SolrCore> getCores() {
    List<SolrCore> lst = new ArrayList<SolrCore>();

    synchronized (locker) {
      lst.addAll(cores.values());
      return lst;
    }
  }

  Set<String> getCoreNames() {
    Set<String> set = new TreeSet<String>();

    synchronized (locker) {
      set.addAll(cores.keySet());
      set.addAll(transientCores.keySet());
    }
    return set;
  }

  List<String> getCoreNames(SolrCore core) {
    List<String> lst = new ArrayList<String>();

    synchronized (locker) {
      for (Map.Entry<String, SolrCore> entry : cores.entrySet()) {
        if (core == entry.getValue()) {
          lst.add(entry.getKey());
        }
      }
      for (Map.Entry<String, SolrCore> entry : transientCores.entrySet()) {
        if (core == entry.getValue()) {
          lst.add(entry.getKey());
        }
      }
    }
    return lst;
  }

  /**
   * Gets a list of all cores, loaded and unloaded (dynamic)
   *
   * @return all cores names, whether loaded or unloaded.
   */
  public Collection<String> getAllCoreNames() {
    Set<String> set = new TreeSet<String>();
    synchronized (locker) {
      set.addAll(cores.keySet());
      set.addAll(transientCores.keySet());
      set.addAll(dynamicDescriptors.keySet());
      set.addAll(createdCores.keySet());
    }
    return set;
  }

  SolrCore getCore(String name) {

    synchronized (locker) {
      return cores.get(name);
    }
  }

  protected void swap(String n0, String n1) {

    synchronized (locker) {
      SolrCore c0 = cores.get(n0);
      SolrCore c1 = cores.get(n1);
      if (c0 == null)
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No such core: " + n0);
      if (c1 == null)
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No such core: " + n1);
      cores.put(n0, c1);
      cores.put(n1, c0);

      c0.setName(n1);
      c0.getCoreDescriptor().putProperty(CoreDescriptor.CORE_NAME, n1);
      c1.setName(n0);
      c1.getCoreDescriptor().putProperty(CoreDescriptor.CORE_NAME, n0);
    }

  }

  protected SolrCore remove(String name, boolean removeOrig) {

    synchronized (locker) {
      SolrCore tmp = cores.remove(name);
      SolrCore ret = null;
      if (removeOrig && tmp != null) {
        coreToOrigName.remove(tmp);
      }
      ret = (ret == null) ? tmp : ret;
      // It could have been a newly-created core. It could have been a transient core. The newly-created cores
      // in particular should be checked. It could have been a dynamic core.
      tmp = transientCores.remove(name);
      ret = (ret == null) ? tmp : ret;
      tmp = createdCores.remove(name);
      ret = (ret == null) ? tmp : ret;
      dynamicDescriptors.remove(name);
      return ret;
    }
  }

  protected void putCoreToOrigName(SolrCore c, String name) {

    synchronized (locker) {
      coreToOrigName.put(c, name);
    }

  }

  protected void removeCoreToOrigName(SolrCore newCore, SolrCore core) {

    synchronized (locker) {
      String origName = coreToOrigName.remove(core);
      if (origName != null) {
        coreToOrigName.put(newCore, origName);
      }
    }
  }

  protected SolrCore getCoreFromAnyList(String name) {
    SolrCore core;

    synchronized (locker) {
      core = cores.get(name);
      if (core != null) {
        return core;
      }

      if (dynamicDescriptors.size() == 0) {
        return null; // Nobody even tried to define any transient cores, so we're done.
      }
      // Now look for already loaded transient cores.
      return transientCores.get(name);
    }
  }

  protected CoreDescriptor getDynamicDescriptor(String name) {
    synchronized (locker) {
      return dynamicDescriptors.get(name);
    }
  }

  protected boolean isLoaded(String name) {
    synchronized (locker) {
      if (cores.containsKey(name)) {
        return true;
      }
      if (transientCores.containsKey(name)) {
        return true;
      }
    }
    return false;

  }

  protected CoreDescriptor getUnloadedCoreDescriptor(String cname) {
    synchronized (locker) {
      CoreDescriptor desc = dynamicDescriptors.get(cname);
      if (desc == null) {
        return null;
      }
      return new CoreDescriptor(desc);
    }

  }

  protected String getCoreToOrigName(SolrCore solrCore) {
    synchronized (locker) {
      return coreToOrigName.get(solrCore);
    }
  }

  protected void publishCoresAsDown(ZkController zkController) {
    synchronized (locker) {
      for (SolrCore core : cores.values()) {
        try {
          zkController.publish(core.getCoreDescriptor(), ZkStateReader.DOWN);
        } catch (KeeperException e) {
          CoreContainer.log.error("", e);
        } catch (InterruptedException e) {
          CoreContainer.log.error("", e);
        }
      }
      for (SolrCore core : transientCores.values()) {
        try {
          zkController.publish(core.getCoreDescriptor(), ZkStateReader.DOWN);
        } catch (KeeperException e) {
          CoreContainer.log.error("", e);
        } catch (InterruptedException e) {
          CoreContainer.log.error("", e);
        }
      }
    }
  }
  
  // Irrepressably ugly bit of the transition in SOLR-4196, but there as at least one test case that follows
  // this path, presumably it's there for a reason.
  // This is really perverse, but all we need the here is to call a couple of static methods that for back-compat
  // purposes
  public void persistCores(Config cfg, Properties containerProperties,
      Map<String,String> rootSolrAttribs, Map<String,String> coresAttribs,
      File file, File configFile, SolrResourceLoader loader) throws XPathExpressionException {
    // This is expensive in the maximal case, but I think necessary. It should
    // keep a reference open to all of the
    // current cores while they are saved. Remember that especially the
    // transient core can come and go.
    //
    // TODO: 5.0. remove the possibility of storing core descriptors in
    // solr.xml?
    //
    
    List<SolrXMLSerializer.SolrCoreXMLDef> solrCoreXMLDefs = new ArrayList<SolrXMLSerializer.SolrCoreXMLDef>();
    synchronized (locker) {
      
      persistCores(cfg, cores, loader, solrCoreXMLDefs);
      persistCores(cfg, transientCores, loader, solrCoreXMLDefs);
      // add back all the cores that aren't loaded, either in cores or transient
      // cores
      for (Map.Entry<String,CoreDescriptor> ent : dynamicDescriptors.entrySet()) {
        if (!cores.containsKey(ent.getKey())
            && !transientCores.containsKey(ent.getKey())) {
          addPersistOneCore(cfg, loader, ent.getValue(), null, solrCoreXMLDefs);
        }
      }
      for (Map.Entry<String,SolrCore> ent : createdCores.entrySet()) {
        if (!cores.containsKey(ent.getKey())
            && !transientCores.containsKey(ent.getKey())
            && !dynamicDescriptors.containsKey(ent.getKey())) {
          addPersistOneCore(cfg, loader, ent.getValue().getCoreDescriptor(),
              null, solrCoreXMLDefs);
        }
      }

      SolrXMLSerializer.SolrXMLDef solrXMLDef = new SolrXMLSerializer.SolrXMLDef();
      solrXMLDef.coresDefs = solrCoreXMLDefs;
      solrXMLDef.containerProperties = containerProperties;
      solrXMLDef.solrAttribs = rootSolrAttribs;
      solrXMLDef.coresAttribs = coresAttribs;
      SOLR_XML_SERIALIZER.persistFile(file, solrXMLDef);
    }
    
  }
  // Wait here until any pending operations (load, unload or reload) are completed on this core.
  protected SolrCore waitAddPendingCoreOps(String name) {

    // Keep multiple threads from operating on a core at one time.
    synchronized (locker) {
      boolean pending;
      do { // Are we currently doing anything to this core? Loading, unloading, reloading?
        pending = pendingCoreOps.contains(name); // wait for the core to be done being operated upon
        if (! pending) { // Linear list, but shouldn't be too long
          for (SolrCore core : pendingCloses) {
            if (core.getName().equals(name)) {
              pending = true;
              break;
            }
          }
        }
        if (container.isShutDown()) return null; // Just stop already.

        if (pending) {
          try {
            locker.wait();
          } catch (InterruptedException e) {
            return null; // Seems best not to do anything at all if the thread is interrupted
          }
        }
      } while (pending);
      // We _really_ need to do this within the synchronized block!
      if (! container.isShutDown()) {
        if (! pendingCoreOps.add(name)) {
          CoreContainer.log.warn("Replaced an entry in pendingCoreOps {}, we should not be doing this", name);
        }
        return getCoreFromAnyList(name); // we might have been _unloading_ the core, so return the core if it was loaded.
      }
    }
    return null;
  }

  // We should always be removing the first thing in the list with our name! The idea here is to NOT do anything n
  // any core while some other operation is working on that core.
  protected void removeFromPendingOps(String name) {
    synchronized (locker) {
      if (! pendingCoreOps.remove(name)) {
        CoreContainer.log.warn("Tried to remove core {} from pendingCoreOps and it wasn't there. ", name);
      }
      locker.notifyAll();
    }
  }


  protected void persistCores(Config cfg, Map<String, SolrCore> whichCores, SolrResourceLoader loader, List<SolrCoreXMLDef> solrCoreXMLDefs) throws XPathExpressionException {
    for (SolrCore solrCore : whichCores.values()) {
      addPersistOneCore(cfg, loader, solrCore.getCoreDescriptor(), getCoreToOrigName(solrCore), solrCoreXMLDefs);
    }
  }
  
  private void addCoreProperty(Map<String,String> coreAttribs, SolrResourceLoader loader, Node node, String name,
      String value, String defaultValue) {
    
    if (node == null) {
      coreAttribs.put(name, value);
      return;
    }
    
    if (node != null) {
      String rawAttribValue = DOMUtil.getAttr(node, name, null);

      if (value == null) {
        coreAttribs.put(name, rawAttribValue);
        return;
      }
      if (rawAttribValue == null && defaultValue != null && value.equals(defaultValue)) {
        return;
      }
      if (rawAttribValue != null && value.equals(DOMUtil.substituteProperty(rawAttribValue, loader.getCoreProperties()))){
        coreAttribs.put(name, rawAttribValue);
      } else {
        coreAttribs.put(name, value);
      }
    }

  }

  protected void addPersistOneCore(Config cfg, SolrResourceLoader loader,
      CoreDescriptor dcore, String origCoreName,
      List<SolrCoreXMLDef> solrCoreXMLDefs) throws XPathExpressionException {
    
    String coreName = dcore.getProperty(CoreDescriptor.CORE_NAME);
    
    Map<String,String> coreAttribs = new HashMap<String,String>();

    CloudDescriptor cd = dcore.getCloudDescriptor();
    String collection = null;
    if (cd != null) collection = cd.getCollectionName();

    if (origCoreName == null) {
      origCoreName = coreName;
    }
    
    Properties properties = dcore.getCoreProperties();
    Node node = null;
    if (cfg != null) {
      node = cfg.getNode("/solr/cores/core[@name='" + origCoreName + "']",
          false);
    }
    
    coreAttribs.put(CoreDescriptor.CORE_NAME, coreName);
    
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_INSTDIR, dcore.getRawInstanceDir(), null);

    coreAttribs.put(CoreDescriptor.CORE_COLLECTION,
        StringUtils.isNotBlank(collection) ? collection : dcore.getName());
    
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_DATADIR, dcore.getDataDir(), null);
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_ULOGDIR, dcore.getUlogDir(), null);
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_TRANSIENT, Boolean.toString(dcore.isTransient()), null);
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_LOADONSTARTUP, Boolean.toString(dcore.isLoadOnStartup()), null);
    
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_COLLECTION,
        collection, dcore.getName());
    
    String shard = null;
    String roles = null;
    if (cd != null) {
      shard = cd.getShardId();
      roles = cd.getRoles();
    }
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_SHARD,
        shard, null);
    
    addCoreProperty(coreAttribs, loader, node, CoreDescriptor.CORE_ROLES,
        roles, null);

    coreAttribs.put(CoreDescriptor.CORE_LOADONSTARTUP,
        Boolean.toString(dcore.isLoadOnStartup()));
    coreAttribs.put(CoreDescriptor.CORE_TRANSIENT,
        Boolean.toString(dcore.isTransient()));
    

    SolrXMLSerializer.SolrCoreXMLDef solrCoreXMLDef = new SolrXMLSerializer.SolrCoreXMLDef();
    solrCoreXMLDef.coreAttribs = coreAttribs;
    solrCoreXMLDef.coreProperties = properties;
    solrCoreXMLDefs.add(solrCoreXMLDef);

  }

  protected Object getLocker() { return locker; }

  // Be a little careful. We don't want to either open or close a core unless it's _not_ being opened or closed by
  // another thread. So within this lock we'll walk along the list of pending closes until we find something NOT in
  // the list of threads currently being loaded or reloaded. The "usual" case will probably return the very first
  // one anyway..
  protected SolrCore getCoreToClose() {
    synchronized (locker) {
      for (SolrCore core : pendingCloses) {
        if (! pendingCoreOps.contains(core.getName())) {
          pendingCoreOps.add(core.getName());
          pendingCloses.remove(core);
          return core;
        }
      }
    }
    return null;
  }

  protected void addCreated(SolrCore core) {
    synchronized (locker) {
      createdCores.put(core.getName(), core);
    }
  }

  protected String checkUniqueDataDir(String targetPath) {
    // Have to check
    // loaded cores
    // transient cores
    // dynamic cores
    synchronized (locker) {
      for (SolrCore core : cores.values()) {
        if (targetPath.equals(core.getDataDir())) return core.getName();
      }
      for (SolrCore core : transientCores.values()) {
        if (targetPath.equals(core.getDataDir())) return core.getName();
      }
      for (CoreDescriptor desc : dynamicDescriptors.values()) {
        if (targetPath.equals(desc.getDataDir())) return desc.getName();
      }
    }

    return null;
  }
}
