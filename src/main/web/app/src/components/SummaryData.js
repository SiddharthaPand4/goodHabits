import React, {Component} from 'react';
import 'react-table/react-table.css'

import ReactTable from 'react-table'
import {Row, Col} from "reactstrap";
import {Bar} from "react-chartjs-2";

export default class SummaryDataList extends Component {

    constructor(props) {
        super(props);

        this.state = {
            data: [],
            chartdata: null,
            loading: true,
            pages: 0
        };

        this.makeChartData = this.makeChartData.bind(this);
        this.getSummaryData = this.getSummaryData.bind(this);
    }


    getSummaryData(page, pageSize, sorted, filtered, handleRetrievedData) {

        this.setState({
            loading: true
        });

        let requestBody = {
            page: page,
            pageSize: pageSize,
            sorted: sorted,
            filtered: filtered,
        };

        fetch("/api/data/summary", {
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            method: "PUT",
            body: JSON.stringify(requestBody)
        }).then(response => response.json())
            .then(response => {
                    this.makeChartData(response.data);
                    return handleRetrievedData(response);
                }
            );

    }

    makeChartData(data) {
        let chartdata = {
            labels: [],
            datasets: [{
                label: "Data",
                data: []
            }]
        };

        for (let i = 0; i < data.length; i++) {
            chartdata.datasets[0].data.push(data[i].count);
            chartdata.labels.push(data[i].type);
        }
        this.setState({chartdata: chartdata})
    }

    render() {



        const chartdata = this.state.chartdata;
        const data = this.state.data;
        const pages = this.state.pages;
        const loading = this.state.loading;
        const chartComponent = this.state.loading ? (<div>Loading...</div>) : ( <Bar data={chartdata}/>);
        const columns = [{
            Header: 'Date',
            accessor: 'date',
            id: 'date'
        }, {
            Header: 'From',
            accessor: 'from',
            id: 'from'
        }, {
            Header: 'To',
            accessor: 'to',
            id: 'to'
        }, {
            Header: 'Span',
            accessor: 'span',
            id: 'span'
        }, {
            Header: 'Type',
            accessor: 'type',
            id: 'type',
            Cell: props => <span className='number'>{props.value}</span> // Custom cell components!
        }, {
            Header: 'Count',
            accessor: 'count', // Custom value accessors!
            id: 'count', // Required because our accessor is not a string
        }
        ];



        return (

            <Row>
                <Col>
                    <ReactTable
                        defaultPageSize={10}
                        data={data}
                        columns={columns}
                        pages={pages}
                        className="-striped -highlight"
                        loading={loading}
                        showPagination={true}
                        showPaginationTop={false}
                        showPaginationBottom={true}
                        pageSizeOptions={[5, 10, 20, 25, 50, 100]}
                        manual // this would indicate that server side pagination has been enabled
                        onFetchData={(state, instance) => {
                            this.setState({loading: true});
                            this.getSummaryData(state.page, state.pageSize, state.sorted, state.filtered, (res) => {

                                this.setState({
                                    data: res.data,
                                    pages: Math.ceil(res.totalElements / parseFloat(state.pageSize)),
                                    loading: false
                                })
                            });
                        }}
                    />
                </Col>
                <Col>
                    <br/>
                    <br/>
                    <br/>
                    {chartComponent}
                </Col>
            </Row>)
    }
}