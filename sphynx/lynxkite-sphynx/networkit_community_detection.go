// All NetworKit ops that make a partition for a graph.
package main

import (
	"fmt"

	"github.com/lynxkite/lynxkite/sphynx/networkit"
)

func init() {
	operationRepository["NetworKitCommunityDetection"] = Operation{
		execute: func(ea *EntityAccessor) (err error) {
			defer func() {
				if e := recover(); e != nil {
					err = fmt.Errorf("%v", e)
					panic(e)
				}
			}()
			vs := ea.getVertexSet("vs")
			es := ea.getEdgeBundle("es")
			weight := ea.getDoubleAttributeOpt("weight")
			o := &NetworKitOptions{ea.GetMapParam("options")}
			seed := uint64(1)
			if s, exists := o.Options["seed"]; exists {
				seed = uint64(s.(float64))
			}
			networkit.SetSeed(seed, true)
			networkit.SetThreadsFromEnv()
			// The caller can set "directed" to false to create an undirected graph.
			g := ToNetworKit(vs, es, weight, o.Options["directed"] != false)
			defer networkit.DeleteGraph(g)
			var p networkit.Partition
			switch ea.GetStringParam("op") {
			case "PLM":
				c := networkit.NewPLM(g, false, o.Double("gamma"))
				defer networkit.DeletePLM(c)
				c.Run()
				p = c.GetPartition()
				defer networkit.DeletePartition(p)
			}
			vs = &VertexSet{}
			vs.MappingToUnordered = make([]int64, p.NumberOfSubsets())
			for i := range vs.MappingToUnordered {
				vs.MappingToUnordered[i] = int64(i)
			}
			es = &EdgeBundle{}
			es.EdgeMapping = make([]int64, p.NumberOfElements())
			es.Src = make([]SphynxId, p.NumberOfElements())
			es.Dst = make([]SphynxId, p.NumberOfElements())
			v := p.GetVector()
			defer networkit.DeleteUint64Vector(v)
			for i := range es.EdgeMapping {
				es.EdgeMapping[i] = int64(i)
				es.Src[i] = SphynxId(i)
				es.Dst[i] = SphynxId(v.Get(i))
			}
			ea.output("partitions", vs)
			ea.output("belongsTo", es)
			return
		},
	}
}
