import org.apache.spark.storage.StorageLevel

lynx.sparkTests.iterativeTest(
  storageLevel: "MEMORY_AND_DISK",
  dataSize: params.dataSize)
