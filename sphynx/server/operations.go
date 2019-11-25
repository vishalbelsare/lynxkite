// Implementations of Sphynx operations.

package main

type Operation struct {
	execute func(*Server, OperationInstance) map[string]interface{}
}

var operations = map[string]Operation{
	"ExampleGraph": exampleGraph,
	// "PageRank":     exampleGraph
}

var exampleGraph = Operation{
	execute: func(s *Server, opInst OperationInstance) map[string]interface{} {
		outputs := make(map[string]interface{})

		vertexMapping := []int64{0, 1, 2, 3}
		outputs["vertices"] = VertexSet{vertexMapping}
		outputs["edges"] = EdgeBundle{
			src:           []int64{0, 1, 2, 2},
			dst:           []int64{1, 0, 0, 1},
			vertexMapping: vertexMapping,
			edgeMapping:   []int64{0, 1, 2, 3},
		}
		outputs["age"] = Attribute{
			attribute: []float64{20.3, 18.2, 50.3, 2.0},
			defined:   []bool{true, true, true, true},
		}
		outputs["gender"] = Attribute{
			attribute: []string{"Male", "Female", "Male", "Male"},
			defined:   []bool{true, true, true, true},
		}
		outputs["income"] = Attribute{
			attribute: []float64{1000, 0, 0, 2000},
			defined:   []bool{true, false, false, true},
		}
		outputs["location"] = Attribute{
			attribute: []struct {
				x float64
				y float64
			}{
				{x: 40.71448, y: -74.00598},      // New York
				{x: 47.5269674, y: 19.0323968},   // Budapest
				{x: 1.352083, y: 103.819836},     // Singapore
				{x: -33.8674869, y: 151.2069902}, // Sydney
			},
			defined: []bool{true, true, true, true},
		}
		outputs["comment"] = Attribute{
			attribute: []string{"Adam loves Eve", "Eve loves Adam",
				"Bob envies Adam", "Bob loves Eve"},
			defined: []bool{true, true, true, true},
		}
		outputs["weight"] = Attribute{
			attribute: []float64{1, 2, 3, 4},
			defined:   []bool{true, true, true, true},
		}
		outputs["greeting"] = "Hello world! 😀 "
		return outputs
	},
}
